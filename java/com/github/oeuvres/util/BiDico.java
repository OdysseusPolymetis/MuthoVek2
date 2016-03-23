package com.github.oeuvres.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * A specialized bi-directional Map <int, String> for a dictionary of terms.
 * 
 * Each term added will create an entry or increment a counter if already
 * exists. Each term could be accessed by an int id, generated by an
 * autoincrement pointer. It’s a grow only object, entries can’t be removed.
 * There’s no method to put a term with a free key, terms are never replaced and
 * keep their index. Ids are kept consistent during all life of Object, but may
 * be lost on saving and dictionary merges.
 * 
 * @author glorieux-f
 *
 */
public class BiDico
{
  /** Position of the term counter in the array of int values */
  public static final int COUNT_POS = 0;
  /** Position of the term index in the array of int values */
  public static final int INDEX_POS = 1;
  /** Pointer in the array, only growing when terms are added */
  private int pointer;
  /** HashMap to find String fast, int array is a hack to have an object */
  private HashMap<String, int[]> map;
  /** List of terms, kept in index order, to get a term by int index */
  private String[] array;
  /** Current working value, speed +3% */
  int[] value;
  /** Count of all occurrences */
  private int sum;

  /**
   * Constructor
   */
  public BiDico()
  {
    pointer = 0;
    map = new HashMap<String, int[]>();
    array = new String[32];
  }

  /**
   * Get a term by index
   * 
   * @param index
   * @return the term
   */
  public String term( int index )
  {
    if (index < 1)
      return null;
    if (index > pointer)
      return null;
    return array[index];
  }

  /**
   * Get the index of a term, 0 if not found
   * 
   * @param a term
   * @return the key
   */
  public int index( String term )
  {
    value = map.get( term );
    if (value == null)
      return 0;
    return value[INDEX_POS];
  }

  /**
   * Get the count of a term, 0 if not found
   * 
   * @param a
   *          term
   * @return the count of occurrences
   */
  public int count( String term )
  {
    value = map.get( term );
    if (value == null)
      return 0;
    return value[COUNT_POS];
  }

  /**
   * Get the count of a term by index, 0 if not found
   * 
   * @param a
   *          term id
   * @return the state of counter after increments
   */
  public int count( int index )
  {
    value = map.get( array[index] );
    if (value == null)
      return 0;
    return value[COUNT_POS];
  }

  /**
   * Increment a term, create it if not exists
   * 
   * @param term
   * @return the index created
   */
  public int add( String term )
  {
    return add( term, 1 );
  }

  /**
   * Multiple occurrences to add, return its index, increment counter if already
   * found Here, be fast!
   */
  public int add( String term, int count )
  {
    sum += count;
    value = map.get( term );
    if (value == null) {
      pointer++;
      // index is too short, extends it (not a big perf pb)
      if (pointer >= array.length) {
        final int oldLength = array.length;
        final String[] oldData = array;
        array = new String[oldLength * 2];
        System.arraycopy( oldData, 0, array, 0, oldLength );
      }
      map.put( term, new int[] { count, pointer } );
      array[pointer] = term;
      return pointer;
    }
    value[COUNT_POS] += count; // increment counter by reference
    return value[INDEX_POS];
  }

  /**
   * Size of the dictionary
   */
  public int size()
  {
    return pointer;
  }

  /**
   * Sum of all counts
   */
  public int sum()
  {
    return sum;
  }

  /**
   * Used for freqlist, return a view o the map sorted by term count
   */
  public List<Map.Entry<String, int[]>> freqlist()
  {
    List<Map.Entry<String, int[]>> list = new LinkedList<Map.Entry<String, int[]>>( map.entrySet() );
    Collections.sort( list, new Comparator<Map.Entry<String, int[]>>()
    {
      @Override
      public int compare( Map.Entry<String, int[]> o1, Map.Entry<String, int[]> o2 )
      {
        return o2.getValue()[COUNT_POS] - o1.getValue()[COUNT_POS];
      }
    } );
    return list;
  }

  /**
   * To save the dictionary, with some index consistency but… will not works on
   * merge
   * 
   * @param file
   * @throws NumberFormatException
   * @throws IOException
   */
  public void csv( Path path ) throws IOException
  {
    BufferedWriter writer = Files.newBufferedWriter( path, Charset.forName( "UTF-8" ) );
    csv( writer );
  }

  /**
   * Send a CSV version of the dictionary
   * 
   * @return a CSV string
   */
  public String csv()
  {
    return csv( 0, null );
  }

  /**
   * Send a CSV version of the dictionary
   * 
   * @return a CSV string
   */
  public String csv( int limit )
  {
    return csv( limit, null );
  }

  /**
   * Send a CSV version of the dictionary
   * 
   * @return a CSV string
   */
  public String csv( int size, Set<String> stoplist )
  {
    String ret = null;
    try {
      ret = csv( new StringWriter(), size, stoplist ).toString();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return ret;
  }

  /**
   * Give a csv view of all dictionary
   * 
   * @throws IOException
   */
  public Writer csv( Writer writer ) throws IOException
  {
    return csv( writer, 0, null );
  }

  /**
   * Give a csv view of all dictionary
   * 
   * @throws IOException
   */
  public Writer csv( Writer writer, int limit, Set<String> stoplist ) throws IOException
  {
    List<Map.Entry<String, int[]>> list = freqlist();
    int size = list.size();
    String term;
    try {
      writer.write( "TERM\tCOUNT\tKEY\n" );
      for (int i = 0; i < size; i++) {
        Map.Entry<String, int[]> entry = list.get( i );
        term = entry.getKey();
        if (stoplist != null && stoplist.contains( term ))
          continue;
        writer.write( term + "\t" + entry.getValue()[COUNT_POS] + "\t" + entry.getValue()[INDEX_POS] + "\n" );
        if (limit-- == 0)
          break;
      }
    } finally {
      writer.close();
    }
    return writer;
  }

  /**
   * Is used for debug, is not a save method
   */
  @Override
  public String toString()
  {
    return csv( 10 );
  }

  /**
   * Load a freqlist from csv TODO test it
   * 
   * @param file
   * @throws IOException
   * @throws NumberFormatException
   */
  public void load( Path path ) throws IOException
  {
    BufferedReader reader = Files.newBufferedReader( path, Charset.forName( "UTF-8" ) );
    String line = null;
    int value;
    try {
      // pass first line
      line = reader.readLine();
      while ((line = reader.readLine()) != null) {
        if (line.contains( "\t" )) {
          String[] strings = line.split( "\t" );
          try {
            value = Integer.parseInt( strings[1] );
          } catch (NumberFormatException e) {
            continue;
          }
          add( strings[0].trim(), value );
        } else {
          add( line.trim() );
        }
      }
    } finally {
      reader.close();
    }
  }

  /**
   * Testing
   * 
   * @throws IOException
   */
  public static void main( String[] args ) throws IOException
  {
    BiDico dic = new BiDico();
    Path context = Paths.get( BiDico.class.getClassLoader().getResource( "" ).getPath() ).getParent();
    Path textfile = Paths.get( context.toString(), "/textes/zola.txt" );
    System.out.print( "Parse: " + textfile + "... " );
    // un peu plus rapide de charger la chaine d’un coup
    // la mise en minuscule ici est plus rapide mais brutale
    // un bon Tokenizer saurait ne diminuer que les mots de la langue
    String text = new String( Files.readAllBytes( textfile ), StandardCharsets.UTF_8 ).toLowerCase();
    Scanner scan = new Scanner( text );
    scan.useDelimiter( "\\PL+" );
    long start = System.nanoTime();
    while (scan.hasNext()) {
      dic.add( scan.next() );
    }
    scan.close();
    System.out.println( ((System.nanoTime() - start) / 1000000) + " ms" );
    Path stopfile = Paths.get( context.toString(), "/res/fr-stop.txt" );
    Set<String> stoplist = new HashSet<String>( Files.readAllLines( stopfile, StandardCharsets.UTF_8 ) );
    System.out.println( "Tokens: " + dic.sum() + " Forms: " + dic.size() + "  " );
    System.out.println( dic.csv( 100, stoplist ) );
    Path dicpath = Paths.get( context.toString(), "/zola-dic.csv" );
    dic.csv( dicpath );
    System.out.println( "Dico saved in: " + dicpath );
    // TODO test reload
  }
}