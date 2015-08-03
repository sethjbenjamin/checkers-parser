import java.util.ArrayList;
import java.util.List;

import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.util.*;

public class BoardParser
{
	private RulesParser parent;
	private List<CoreMap> sentences;
	private String[][] lemmas; //lemmas[i][j] holds the lemma of the jth word in the ith sentence of the text
	private String[][] partsOfSpeech; //partsOfSpeech[i][j] holds the POS of the jth word in the ith sentence of the text

	private int[] dimensions;
	private String[][] initialBoard;
	private String[][] transitionZones;

	public BoardParser(RulesParser parent, List<CoreMap> sentences, String[][] lemmas, String[][] partsOfSpeech)
	{
		this.parent = parent;
		this.sentences = sentences;
		this.lemmas = lemmas;
		this.partsOfSpeech = partsOfSpeech;

		this.dimensions = new int[2];
	}

	public void parseBoard()
	{
		parseDimensions();
		parseInitialSetup();
	}

	public String[][] getInitialBoard()
	{
		return initialBoard;
	}

	public String[][] getTransitionZones()
	{
		return transitionZones;
	}

	public void parseDimensions()
	{
		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap sentence = sentences.get(i);

			ArrayList<Integer> indicesOfBoard = new ArrayList<Integer>(1);
			int numberIndex = -1;
			int rows = 0;
			int columns = 0;
			//iterate over all lemmas of the ith sentence
			for (int j = 0; j < lemmas[i].length; j++)
			{
				String lemma = lemmas[i][j];
				/*any number that is dominated by a synonym or hyponym of "board" potentially denotes dimensions,
				so we have to determine the indices within sentence i of all synonyms/hyponyms of "board" */
				if (RulesParser.isSynonymOf("board", lemma) || RulesParser.isHypernymOf("board", lemma))
					indicesOfBoard.add(new Integer(j));

				//search for any word of the form MxN where M and N are integers
				if (lemma.contains("x")) //only need to check for lowercase, as all our lemmas have only lowercase symbols
				{
					int xIndex = lemma.indexOf("x"); 
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
				dimensions[0] = rows;
				dimensions[1] = columns;
				break;
			}

			/* If we get to this point, then we haven't found a "MxN" in this sentence, so we
			explore the following phrases (with "M" and "N" as integers):
			- "N square" (where "square" is necessarily dominated by or siblings with "board" in the semantic dependency graph)
			- "M rows/ranks" "N columns/files" 
			If multiple such statements are found in the ruleset, the statement that results in the largest number of rows and 
			columns is used (eg, in the sentence "The board consists of 64 squares, alternating between 32 black and 32 red squares"
			only "64 squares" is used, not "32 squares"). */
			//dependencies for current sentence as a String[], each entry containing a single dependency String
			String[] dependencies = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");


			int squareValue = -1;
			int boardIndex = -1;

			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = RulesParser.isolateIndexFromDependency(d,1);
				int index2 = RulesParser.isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1];
				String lemma2 = lemmas[i][index2];

				if (d.contains("nummod("))
				{
					String number2 = sentence.get(CoreAnnotations.TokensAnnotation.class).get(index2).get(
						CoreAnnotations.NormalizedNamedEntityTagAnnotation.class);
					if (number2 != null) // if NER has recognized the second word in the dependency to be a number value
					{
						try
						{
							int value = (int) Double.parseDouble(number2);
							//the following checks if the word being modified by a number is "square"
							if (lemma1.equals("square"))
							{	//check if "square" is either dominated by or siblings with "board" in the semantic dependency graph
								if (parent.dominates(i, indicesOfBoard, index1) || parent.isSibling(i, indicesOfBoard, index1))
								{
									int sqrtValue = (int) Math.sqrt(value); // integer value of the number
									if (sqrtValue > dimensions[0] && sqrtValue > dimensions[1])
									{
										dimensions[0] = sqrtValue;
										dimensions[1] = sqrtValue;
									}
								}
							}
							/* In the following sentence: "Checkers is played on a 64 square board." CoreNLP erroneously tags "square" 
							as an adjective, thus considering 64 to modify "board". Unfortunately, this is really common, so we have to
							account for this. The following checks if the word being modified by a number is "board." 
							The following simply stores the index of the word "board" and the value of the number supposedly modifying it
							such that they can be used later. */
							else if (lemma1.equals("board"))
							{
								squareValue = value;
								boardIndex = index1;
							}
							else if (lemma1.equals("row") || lemma1.equals("rank"))
							{
								if (value > dimensions[0])
									dimensions[0] = value;
							}
							else if (lemma1.equals("column") || lemma1.equals("file"))
							{
								if (value > dimensions[1])
									dimensions[1] = value;
							}
						}
						catch (NumberFormatException e)
						{ /* ignore; if Double.parseDouble(number2) throws this exception, we just ignore this dependency */ }
					}
				}
				/* The following is to account for the aforementioned bug in which CoreNLP erroneously analyzes the construction 
				"64 square board" as consisting of two dependencies: nummod(board, 64) and amod(board, square). 
				The following checks if:
				- index1 == boardIndex (the only way for this to be true is if boardIndex has been changed from its initial value of -1,
				 which can only happen if we have already processed the nummod(board, 64) dependency
				- lemma2 is square - the "modifying adjective" is square
				- squarevalue is positive - again, only occurs if we have already processed nummod(board, 64) */
				else if (d.contains("amod("))
				{
					if (index1 == boardIndex && lemma2.equals("square") && squareValue > 0)
					{
						/* We don't need to check if square is dominated by board, as a) it already is shown to do so, since it is
						considered an adjective modifying board and b) that information is not meaningful as long as CoreNLP is incorrect*/
						int sqrtValue = (int) Math.sqrt(squareValue); // integer value of the number
						if (sqrtValue > dimensions[0] && sqrtValue > dimensions[1])
						{
							dimensions[0] = sqrtValue;
							dimensions[1] = sqrtValue;
						}

					}

				}
			}
		}
		System.out.println("rows: " + dimensions[0] + ", columns: " + dimensions[1]); //debugging
		initialBoard = new String[dimensions[0]][dimensions[1]];
		transitionZones = new String[dimensions[0]][dimensions[1]];
	}

	public void parseInitialSetup()
	{
		//TODO: THIS IS ALL HARDCODED - PLACEHOLDERS! Must be replaced with actual parsing from ruleset.
		for (int i = 0; i < 3; i++)
			for (int j = (i+1)%2; j < initialBoard[i].length; j+=2)
				initialBoard[i][j] = "P1";
		for (int i = initialBoard.length-1; i > initialBoard.length-4; i--)
			for (int j = (i+1)%2; j < initialBoard[i].length; j+=2)
				initialBoard[i][j] = "P2";
	}
}