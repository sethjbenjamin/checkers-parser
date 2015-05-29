import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;

import edu.stanford.nlp.io.*;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.util.*;

public class RulesParser
{
	private String fileName;

	//Stanford CoreNLP tools
	private StanfordCoreNLP pipeline;
	private Annotation annotation;
	private List<CoreMap> sentences;


	public RulesParser(String fileName)
	{
		this.fileName = fileName;

		// creates a StanfordCoreNLP object, with sentence splitting, POS tagging, lemmatization, and syntactic dependency parsing
		Properties annotators = new Properties();
		annotators.put("annotators", "tokenize, ssplit, pos, lemma, depparse");
		pipeline = new StanfordCoreNLP(annotators);
	}

	public void readFile()
	{
		try
		{
			annotation = new Annotation(IOUtils.slurpFile(fileName));
			pipeline.annotate(annotation);

			sentences = annotation.get(CoreAnnotations.SentencesAnnotation.class);
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}

	}

	public void parse()
	{
		readFile();
		ArrayList<Direction> motionTypes = parseMotion();

		
	}

	public ArrayList<Direction> parseMotion()
	{
		ArrayList<Direction> motionTypes = new ArrayList<Direction>(1); 
		//ultimately, this ArrayList will hold all of the allowed types of motion explicitly described in the ruleset

		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap current = sentences.get(i);
			//We create an ArrayList of the lemmas of each word in "current", stored as Strings.
			ArrayList<String> lemmas = new ArrayList<String>(1);
			for(CoreMap token: current.get(CoreAnnotations.TokensAnnotation.class)) //iterate over each word
				lemmas.add(token.get(CoreAnnotations.LemmaAnnotation.class)); //add its lemma to the ArrayList
			//Now we create an array of the basic dependencies of "current"
			//the next line simply initializes "dependeciesString" with a String containing all the dependencies of "current"
			String dependenciesString = current.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(SemanticGraph.OutputFormat.LIST);
			String[] dependencies = dependenciesString.split("\n"); //split the String into an array, one dependency per entry in the array
			//iterate over all dependencies, searching for certain types
			for (String d: dependencies)
			{
				if (d.contains("advmod"))
				{
					/* We want to determine if the verb being modified by an adverb is any inflection of the lexeme "move", so we have to
					isolate the verb's index in the sentence as a substring of the dependency string. */
					int endIndexNum = d.indexOf(","); //index of the comma immediately following the number
					int startIndexNum = d.lastIndexOf("-", endIndexNum) + 1; //index of the first digit of the number corresponding to the verb being modified

					int verbIndex = Integer.parseInt(d.substring(startIndexNum,endIndexNum));

					if (lemmas.get(verbIndex-1).equals("move")) // if the lemma of the modified adverb is move
					{
						//We now have to isolate the modifying adverb as a substring.
						int startIndexAdv = d.indexOf(' ') + 1; //index in the first character of the modifying adverb in s
						int endIndexAdv = d.lastIndexOf('-'); //index of hyphen immediately following modifying adverb in s

						String adverb = d.substring(startIndexAdv,endIndexAdv);
						switch (adverb) //TODO: synonyms of each adverb (use wordnet?)
						{
							case "diagonally":
								motionTypes.add(Direction.DIAGONAL);
								System.out.println("Sentence " + i + ": As an adverb, " + "Diagonal motion added."); //debugging
								break;
							case "forward":
								motionTypes.add(Direction.FORWARD);
								System.out.println("Sentence " + i + ": As an adverb, " + "Forward motion added."); //debugging
								break;
							case "backward":
								motionTypes.add(Direction.BACKWARD);
								System.out.println("Sentence " + i + ": As an adverb, " + "Backward motion added."); //debugging
								break;
							case "left":
								motionTypes.add(Direction.LEFT);
								System.out.println("Sentence " + i + ": As an adverb, " + "Leftward motion added."); //debugging
								break;
							case "right":
								motionTypes.add(Direction.RIGHT);
								System.out.println("Sentence " + i + ": As an adverb, " + "Rightward motion added."); //debugging
								break;
							
						}
					}
				}
				else if (d.contains("amod"))
				{
					/* We want to determine if the noun being modified by an adjective is any synonym of the lexemes "move (n)" or "direction (n)", 
					so we have to isolate the noun's index in the sentence as a substring of the dependency string. */
					int endIndexNum = d.indexOf(","); //index of the comma immediately following the number
					int startIndexNum = d.lastIndexOf("-", endIndexNum) + 1; //index of the first digit of the number corresponding to the noun being modified

					int nounIndex = Integer.parseInt(d.substring(startIndexNum,endIndexNum));

					if (lemmas.get(nounIndex-1).equals("move") || lemmas.get(nounIndex-1).equals("direction")) // TODO: still need to implement wordnet
					{
						//We now isolate the modifiying adjective as a substring.
						int startIndexAdj = d.indexOf(' ') + 1; //index in the first character of the modifying adjective in s
						int endIndexAdj = d.lastIndexOf('-'); //index of hyphen immediately following modifying adjective in s

						String adj = d.substring(startIndexAdj, endIndexAdj);
						switch (adj)
						{
							case "diagonal":
								motionTypes.add(Direction.DIAGONAL);
								System.out.println("Sentence " + i + ": As an adjective, " + "Diagonal motion added."); //debugging
								break;
							case "forward":
								motionTypes.add(Direction.FORWARD);
								System.out.println("Sentence " + i + ": As an adjective, " + "Forward motion added."); //debugging
								break;
							case "backward":
								motionTypes.add(Direction.BACKWARD);
								System.out.println("Sentence " + i + ": As an adjective, " + "Backward motion added."); //debugging
								break;
							case "left":
								motionTypes.add(Direction.LEFT);
								System.out.println("Sentence " + i + ": As an adjective, " + "Leftward motion added."); //debugging
								break;
							case "right":
								motionTypes.add(Direction.RIGHT);
								System.out.println("Sentence " + i + ": As an adjective, " + "Rightward motion added."); //debugging
								break;
						}
					}

				}
			}
		}
		return motionTypes;
	}

	public enum Direction
	{
		FORWARD, BACKWARD, LEFT, RIGHT, DIAGONAL
	}



	
}