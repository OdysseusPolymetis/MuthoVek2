package fr.muthovek;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import com.github.oeuvres.util.BiDico;
import com.github.oeuvres.util.Cosine;
import com.github.oeuvres.util.IntIntMap;
import com.github.oeuvres.util.IntObjectMap;
import com.github.oeuvres.util.IntSlider;

/**
 * Space of a corpus, dictionary with vectors of co-occurrences.
 * Terms are stores int, for efficency and Cosine calculations.
 * 
 */
public class Dicovek {
  /** Uded as attribute in a token stream  */
  public static int STOPWORD = 1;
  /** Tmp, see max vector size */
  private int vekmax;
  /** Size of left context */
  final int left;
  /** Size of right context */
  final int right;
  /** Dictionary in order of indexing for int keys, should be kept private, no external modif */
  private BiDico terms;
  /** Vectors of co-occurences for each term of dictionary */
  private IntObjectMap<IntIntMap> vectors;
  /** Sliding window */
  private IntSlider win;
  /** List of stop words, usually grammatical, do not modify during object life */
  private final HashSet<String> stoplist;
  /** Current Vector to work on */
  private IntIntMap vek;
  /**
   * Simple constructor
   */
  public Dicovek(int contextLeft, int contextRight) {
    this(contextLeft, contextRight, null, 5000);
  }
  public Dicovek(int contextLeft, int contextRight, HashSet<String> stoplist) {
    this(contextLeft, contextRight, stoplist, 5000);
  }
  
  /**
   * Full constructor with all options
   */
  public Dicovek(int contextLeft, int contextRight, HashSet<String> stoplist, int initialSize) {    
    left = contextLeft;
    right = contextRight;
    this.stoplist = stoplist;    
    terms = new BiDico();
    // 44960 is the size of all Zola vocabulary
    vectors = new IntObjectMap<IntIntMap>(initialSize);
    win = new IntSlider(contextLeft, contextRight);
  }
  /**
   * Add a term and do good work
   * Most of the logic is here
   * Add empty positions with ""
   * 
   * @param term A token
   */
  public Dicovek add(String term) {
    // default is an empty position
    int termid = 0;
    // add term to the dictionary and gets its int id
    if (!term.equals("")) termid = terms.add(term);
    // no stop list, add simple term
    if (stoplist == null) win.addRight(termid);
    // term is a stop word, add an attribute to the window position
    else if(stoplist.contains(term)) win.addRight(termid, STOPWORD);
    // don’t forget default 
    else win.addRight(termid);
    
    // get center of window, and work around
    termid = win.get(0);
    // center is not set, crossing an empty sequence, maybe: start, end, paragraph break...  
    if (termid == 0) return this;
    // do not record stopword vector
    if (win.getAtt(0) == STOPWORD) return this;
    // get the vector for this center term
    vek = vectors.get(termid);
    // optimize ? term not yet encountered, create vector
    if (vek == null) {
      vek = new IntIntMap(10);
      vectors.put(termid, vek);
    }
    // fill the vector, using the convenient inc method
    for (int i=-left; i<=right; i++) {
      if (i==0) continue;
      vek.add(win.get(i));
    }
    return this;
  }
  /**
   * Output most frequent words as String
   * TODO, best object packaging
   */
  public String freqlist(boolean stop, int limit) {
    StringBuffer sb = new StringBuffer();
    List<Map.Entry<String, int[]>> list = terms.freqlist();
    Map.Entry<String, int[]> entry;
    boolean first = true;
    String w;    
    for( int i = 0; i < list.size(); i++ ) {
      entry = list.get( i );
      w = entry.getKey();
      if (stoplist.contains( w )) continue;
      if (first) first = false;
      else sb.append( ", " );
      sb.append( w+":"+ entry.getValue()[BiDico.COUNT_POS]); 
      if (--limit == 0) break;
    }
    return sb.toString();
  }
  /**
   * List "syns" by vector proximity, just Cosine for now
   * TODO: good object to give back
   * @throws IOException 
   */
  public ArrayList<SimRow> syns( String term ) throws IOException {
    // get vector for requested word
    int k = terms.index( term );
    if (k == 0) return null;
    IntIntMap vekA = vectors.get( k );
    // some words of the dictionary has no vector but are recorded in co-occurrence (ex: stop)
    if ( vekA == null ) return null;
    // Similarity
    Cosine cosine = new Cosine();
    float score;
    // list dico in freq order
    List<Map.Entry<String, int[]>> list = terms.freqlist();
    Map.Entry<String, int[]> entry;
    int limit = 1000;
    ArrayList<SimRow> table = new ArrayList<SimRow>();
    SimRow row;
    for( int i = 0; i < list.size(); i++ ) {
      entry = list.get( i );
      vek = vectors.get( entry.getValue()[BiDico.INDEX_POS] );
      if ( vek == null ) continue;
      score = (float)cosine.similarity( vekA, vek );
      if (score < 0.7) continue;
      row = new SimRow(entry.getKey(), entry.getValue()[BiDico.COUNT_POS], score);
      table.add( row );
      if (limit-- == 0) break;
    }
    // sort 
    Collections.sort(table, new Comparator<SimRow>() {
      @Override
      public int compare(SimRow row1, SimRow row2)
      {
        return (int)( 100000*(row2.score - row1.score) );
      }
    });
    return table;
  }
  
  public void json(Path path) throws IOException {
    json(path, 0);
  }
  
  public void json(Path path, int limit) throws IOException {
    BufferedWriter writer = Files.newBufferedWriter(
        path, 
        Charset.forName("UTF-8")
    );
    json(writer, limit);
  }
  
  /**
   * Output a string representation of object as Json.
   * TODO, make it loadable.
   * @throws IOException 
   */
  public void json(Writer writer, int limit) throws IOException {
    try {
      writer.write("{\n");
      boolean first1 = true;
      int[][] coocs; // will receive the co-occurrences to sort
      int size;
      // get a sorted Dictionary to loop on
      List<Map.Entry<String, int[]>> list = terms.freqlist();
      Map.Entry<String, int[]> entry;
      int count1 = limit;
      for( int i = 0; i < terms.size(); i++ ) {
        entry = list.get(i);
        // get the vector for this word
        vek = vectors.get(entry.getValue()[BiDico.INDEX_POS]);
        // some words on dictionary has no vector, like stop words
        if ( vek == null ) continue;
        if (first1) first1 = false;
        else writer.append(",\n  ");
        writer.write("  \""+entry.getKey()+"\": {"); // the term
        writer.write(""+entry.getValue()[BiDico.COUNT_POS]); // the term count
        // get vector as an array
        coocs = vek.toArray();
        // sort it before output
        Arrays.sort(coocs, new Comparator<int[]>() {
          @Override
          public int compare(int[] o1, int[] o2) {
            return Integer.compare(o2[1], o1[1]);
          }
        });
        size = coocs.length;
        writer.write(", "+size);
        int count2 = limit;
        for ( int j = 0; j < size; j++ ) {
          writer.write(", ");
          writer.write("\""+terms.term(coocs[j][0])+"\":"+coocs[j][1]);
          if (--count2 == 0) break;
        }
        writer.write("}");
        if (--count1 == 0) break;
      }
      writer.write("\n}");
    }
    finally {
      writer.close();
    }
  }
  /**
   * A row similar word with different info, used for sorting
   * @author user
   *
   */
  class SimRow {
    public final  String term;
    public final int count;
    public final float score;
    public SimRow(String term, int count, float score) {
      this.term = term;
      this.count = count;
      this.score = score;
    }
    public String toString() {
      return term+"\t"+count+"\t"+score;
    }
  }
  
  /**
   * @throws IOException 
   */
  public static void main(String[] args) throws IOException {
    
    Path context = Paths.get(BiDico.class.getClassLoader().getResource("").getPath()).getParent();
    Path textfile = Paths.get( context.toString(), "/Textes/zola.txt");
    System.out.print("Parse: "+textfile+"... ");
    String text = new String(Files.readAllBytes(textfile), StandardCharsets.UTF_8).toLowerCase();
    Scanner scan = new Scanner(text);    
    scan.useDelimiter("\\PL+");
    
    
    Path stopfile = Paths.get( context.toString(), "/res/fr-stop.txt");
    HashSet<String> stoplist = new HashSet<String>(Files.readAllLines(stopfile, StandardCharsets.UTF_8));
    
    int wing = 4;
    Dicovek veks = new Dicovek(wing, wing, stoplist);
    long start = System.nanoTime();
    String w;
    boolean nostop = false;
    // nostop = true;
    while( scan.hasNext() ) {
      w = scan.next();
      if (nostop && stoplist.contains( w )) continue;
      veks.add(w);
    }
    // add empty words here to finish window
    veks.add("").add("").add("");
    scan.close();
    System.out.println( ((System.nanoTime() - start) / 1000000) + " ms");
    System.out.println( veks.freqlist(true, 100) );
    
    Path vekspath = Paths.get( context.toString(), "/zola-veks.json"); 
    veks.json( vekspath, 100 ); // store the vectors in a file
    System.out.println( ((System.nanoTime() - start) / 1000000) + " ms");

    // Boucle de recherche
    BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    List<SimRow> table;
    String filename;
    while (true) {
      System.out.println("Mot: ");
      String word = keyboard.readLine().trim();
      if (word == null || "".equals(word)) System.exit(0);
      start = System.nanoTime();
      table = veks.syns(word);
      if ( table == null ) continue;
      if (nostop) filename = word+"-"+wing+"-nostop.csv";
      else filename = word+"-"+wing+".csv";
      BufferedWriter writer = Files.newBufferedWriter(
          Paths.get( context.toString(), "/"+filename), 
          Charset.forName("UTF-8")
      );
      writer.write( "TERM\tFREQ\tSIM\n" );
      for (SimRow row:table) {
        writer.write(row+"\n");
      }
      writer.write("-\t-\t-\n");
      writer.close();
      // HashMap<String, HashMapContext> dict=initMainDistribDict(word, mapSetMotsFrancaisPost, cosine);
      System.out.println( ((System.nanoTime() - start) / 1000000) + " ms");
    }
  }
  
}