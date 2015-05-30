import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import edu.stanford.nlp.io.*;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.util.*;

import edu.smu.tspell.wordnet.*;

public class RulesParser
{
	private String fileName;
	//Stanford CoreNLP tools:
	private StanfordCoreNLP pipeline;
	private Annotation annotation;
	private List<CoreMap> sentences;
	//WordNet implementation using JAWS:
	private WordNetDatabase wordnet;


	public RulesParser(String fileName)
	{
		this.fileName = fileName;

		// creates a StanfordCoreNLP object, with sentence splitting, POS tagging, lemmatization, and syntactic dependency parsing
		Properties annotators = new Properties();
		annotators.put("annotators", "tokenize, ssplit, pos, lemma, depparse");
		pipeline = new StanfordCoreNLP(annotators);

		wordnet = WordNetDatabase.getFileInstance();
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
		for (int i = 0; i < sentences.size(); i++) //debugging
		{
			CoreMap current = sentences.get(i);
			System.out.println("" + i + ": " + current.get(CoreAnnotations.TextAnnotation.class));
		}
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

			//Now we create an array of the syntactic dependencies of "current"
			//the next line simply initializes "dependeciesString" with a String containing all the dependencies of "current"
			String dependenciesString = current.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(SemanticGraph.OutputFormat.LIST);
			String[] dependencies = dependenciesString.split("\n"); //split the String into an array, one dependency per index in the array

			//iterate over all dependencies, searching for certain types
			for (String d: dependencies)
			{
				if (d.contains("advmod"))
				{
					/* We want to determine if the verb being modified by an adverb is any synonym of the lexeme "move" or "jump", so we first 
					have to determine what the verb is. To do this, we isolate the verb's index in the sentence as a substring of the 
					dependency string. */
					int endIndexNum = d.indexOf(","); //index in "d" of the comma immediately following the modified verb's index
					int startIndexNum = d.lastIndexOf("-", endIndexNum) + 1; //index in "d" of the first digit of the modified verb's index in "current"

					int verbIndex = Integer.parseInt(d.substring(startIndexNum,endIndexNum));

					//Now we determine the lemma of the verb, and see if it is a synonym of "move" or "jump."
					String verbLemma = lemmas.get(verbIndex-1);

					if (isSynonymOf("move", verbLemma) || isSynonymOf("jump", verbLemma))
					{
						//We now have to isolate the modifying adverb as a substring.
						int startIndexAdv = d.indexOf(' ') + 1; //index in "d" of  the first character of the modifying adverb in d
						int endIndexAdv = d.lastIndexOf('-'); //index in "d" of hyphen immediately following modifying adverb in d

						String adverb = d.substring(startIndexAdv,endIndexAdv);

						if (isSynonymOf("diagonally", adverb))
						{
							if (motionTypes.indexOf(Direction.DIAGONAL) < 0) //check to see if this type of motion has already been parsed
								motionTypes.add(Direction.DIAGONAL);
							System.out.println("Sentence " + i + ": As an adjective, " + "Diagonal motion added."); //debugging
						}
						else if (isSynonymOf("forward", adverb))
						{
							if (motionTypes.indexOf(Direction.FORWARD) < 0)
								motionTypes.add(Direction.FORWARD);
							System.out.println("Sentence " + i + ": As an adjective, " + "Forward motion added."); //debugging
						}
						else if (isSynonymOf("backward", adverb))
						{
							if (motionTypes.indexOf(Direction.BACKWARD) < 0)
								motionTypes.add(Direction.BACKWARD);
							System.out.println("Sentence " + i + ": As an adjective, " + "Backward motion added."); //debugging
						}
						else if (isSynonymOf("left", adverb))
						{
							if (motionTypes.indexOf(Direction.LEFT) < 0)
								motionTypes.add(Direction.LEFT);
							System.out.println("Sentence " + i + ": As an adjective, " + "Leftward motion added."); //debugging
						}
						else if (isSynonymOf("right", adverb))
						{
							if (motionTypes.indexOf(Direction.RIGHT) < 0)
								motionTypes.add(Direction.RIGHT);
							System.out.println("Sentence " + i + ": As an adjective, " + "Rightward motion added."); //debugging
						}
					}
				}
				else if (d.contains("amod"))
				{
					/* We want to determine if the noun being modified by an adjective is any synonym of the lexemes "move (n)" or "direction (n)", 
					so we have to isolate the noun's index in the sentence as a substring of the dependency string. */
					int endIndexNum = d.indexOf(","); //index in "d" of the comma immediately following the modified noun's index
					int startIndexNum = d.lastIndexOf("-", endIndexNum) + 1; //index in "d" of the first digit of the modified noun's index in "current"

					int nounIndex = Integer.parseInt(d.substring(startIndexNum,endIndexNum));

					//Now we determine the lemma of the noun, and see if it is a synonym of "move" or "direction."
					String nounLemma = lemmas.get(nounIndex-1);

					if (isSynonymOf("move", nounLemma) || isSynonymOf("direction", nounLemma))
					{
						//We now isolate the modifiying adjective as a substring.
						int startIndexAdj = d.indexOf(' ') + 1; //index in the first character of the modifying adjective in d
						int endIndexAdj = d.lastIndexOf('-'); //index of hyphen immediately following modifying adjective in d

						String adjective = d.substring(startIndexAdj, endIndexAdj);
						
						if (isSynonymOf("diagonal", adjective))
						{
							if (motionTypes.indexOf(Direction.DIAGONAL) < 0) //check to see if this type of motion has already been parsed
								motionTypes.add(Direction.DIAGONAL);
							System.out.println("Sentence " + i + ": As an adjective, " + "Diagonal motion added."); //debugging
						}
						else if (isSynonymOf("forward", adjective))
						{
							if (motionTypes.indexOf(Direction.FORWARD) < 0)
								motionTypes.add(Direction.FORWARD);
							System.out.println("Sentence " + i + ": As an adjective, " + "Forward motion added."); //debugging
						}
						else if (isSynonymOf("backward", adjective))
						{
							if (motionTypes.indexOf(Direction.BACKWARD) < 0)
								motionTypes.add(Direction.BACKWARD);
							System.out.println("Sentence " + i + ": As an adjective, " + "Backward motion added."); //debugging
						}
						else if (isSynonymOf("left", adjective))
						{
							if (motionTypes.indexOf(Direction.LEFT) < 0)
								motionTypes.add(Direction.LEFT);
							System.out.println("Sentence " + i + ": As an adjective, " + "Leftward motion added."); //debugging
						}
						else if (isSynonymOf("right", adjective))
						{
							if (motionTypes.indexOf(Direction.RIGHT) < 0)
								motionTypes.add(Direction.RIGHT);
							System.out.println("Sentence " + i + ": As an adjective, " + "Rightward motion added."); //debugging
						}

					}

				}
			}
		}
		return motionTypes;
	}

	/**
	Tests if "second" is a synonym of "first" by seeing if "second" is one of the 
	word forms given in any WordNet synset of "first."
	*/
	public boolean isSynonymOf(String first, String second)
	{
		Synset[] firstSynsets = wordnet.getSynsets(first);
		for (Synset synset: firstSynsets)
		{
			String[] synonyms = synset.getWordForms();
			if (Arrays.asList(synonyms).contains(second))
				return true;
		}
		return false;
	}

	public enum Direction
	{
		FORWARD, BACKWARD, LEFT, RIGHT, DIAGONAL
	}



	
}