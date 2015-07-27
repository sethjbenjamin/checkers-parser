//import java.io.*;
import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.HashMap;
import java.util.List;
//import java.util.Map;
//import java.util.Properties;

//import edu.stanford.nlp.dcoref.CorefChain;
//import edu.stanford.nlp.dcoref.CorefCoreAnnotations;
import edu.stanford.nlp.ling.*;
//import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.util.*;

public class BoardParser
{
	private RulesParser parent;
	private List<CoreMap> sentences;
	//private Map<Integer, CorefChain> corefChains;
	private String[][] lemmas; //lemmas[i][j] holds the lemma of the jth word in the ith sentence of the text
	private String[][] partsOfSpeech; //partsOfSpeech[i][j] holds the POS of the jth word in the ith sentence of the text

	//private int[][] dimensions;
	//private String[][] board;

	public BoardParser(RulesParser parent, List<CoreMap> sentences, String[][] lemmas, String[][] partsOfSpeech)
	{
		this.parent = parent;
		this.sentences = sentences;
		this.lemmas = lemmas;
		this.partsOfSpeech = partsOfSpeech;

		//this.dimensions = new int[2];
	}

	public int[] parseDimensions()
	{
		int[] dimensions = new int[2];
		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap sentence = sentences.get(i);
			//dependencies for current sentence as a String[], each entry containing a single dependency String
			/*String[] dependencies = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");*/

			ArrayList<Integer> indicesOfBoard = new ArrayList<Integer>(1);
			int numberIndex = -1;
			int rows = 0;
			int columns = 0;
			for (int j = 0; j < lemmas[i].length; j++)
			{
				String lemma = lemmas[i][j];
				/*any number that is dominated by a synonym or hyponym of "board" potentially denotes dimensions,
				so we have to determine the indices within sentence i of all synonyms/hyponyms of "board" */
				if (RulesParser.isSynonymOf("board", lemma) || RulesParser.isHypernymOf("board", lemma))
					indicesOfBoard.add(new Integer(j));

				//search for any word of the form MxN where M and N are integers
				if (lemma.contains("x") || lemma.contains("X"))
				{
					int xIndex = Math.max(lemma.indexOf("x"), lemma.indexOf("X")); 
					/* xIndex is now whichever index is not -1 - the word is very unlikely to contain both x and X 
					(and if it does it will be thrown out when we try to call Integer.parseInt(), so that's fine */

					String preX = lemma.substring(0, xIndex); //substring before the x
					String postX = lemma.substring(xIndex+1); //substring after the x

					//the following tests whether preX and postX are integers (if a NumberFormatException is thrown, they are not)
					try 
					{
						rows = Integer.parseInt(preX);
						columns = Integer.parseInt(postX);
						numberIndex = j;
					}
					catch (NumberFormatException e)
					{/* ignore */}
				}
			}
			if (rows != 0 && columns != 0)
			{
				for (int ind: indicesOfBoard)
				{
					if (parent.dominates(i, ind, numberIndex))
					{
						dimensions[0] = rows;
						dimensions[1] = columns;
					}
				}
			}
		}
		return dimensions;
	}
	
}