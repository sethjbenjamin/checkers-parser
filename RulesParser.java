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
	//WordNet 3.0 implementation using JAWS:
	private WordNetDatabase wordnet;


	public RulesParser(String fileName)
	{
		this.fileName = fileName;

		// creates a StanfordCoreNLP object, with sentence splitting, POS tagging, lemmatization, and parsing
		Properties annotators = new Properties();
		annotators.put("annotators", "tokenize, ssplit, pos, lemma, parse"); //TODO: add NER later to interpret numbers
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

					//Now we determine the lemma of the verb, and see if "move" is a hypernym of it.
					String verbLemma = lemmas.get(verbIndex-1); //-1 because the sentence indices start from 1, not 0

					if (isHypernymOf("move", verbLemma))
					{
						//We now have to isolate the modifying adverb as a substring.
						int startIndexAdv = d.indexOf(' ') + 1; //index in "d" of  the first character of the modifying adverb in d
						int endIndexAdv = d.lastIndexOf('-'); //index in "d" of hyphen immediately following modifying adverb in d

						String adverb = d.substring(startIndexAdv,endIndexAdv);

						/*Now, we call addDirection() to check if "adverb" is a directional adverb, and if so, to
						add it to "motionTypes". */
						addDirection(adverb, motionTypes, i); //TODO: remove i!!!
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
					String nounLemma = lemmas.get(nounIndex-1); //-1 because the sentence indices start from 1, not 0

					if (isSynonymOf("move", nounLemma) || isSynonymOf("direction", nounLemma))
					{
						//We now isolate the modifiying adjective as a substring.
						int startIndexAdj = d.indexOf(' ') + 1; //index in the first character of the modifying adjective in d
						int endIndexAdj = d.lastIndexOf('-'); //index of hyphen immediately following modifying adjective in d

						String adjective = d.substring(startIndexAdj, endIndexAdj);
						
						/*Now, we call addDirection() to check if "adjective" is a directional adjective, and if so, to
						add it to "motionTypes". */
						addDirection(adjective, motionTypes, i); //TODO: remove i!!!
					}

				}
			}
		}
		return motionTypes;
	}

	/**
	Tests if "first" and "second" are synonyms by seeing if "second" is one of the 
	word forms given in specified WordNet synsets of "first." The parameter "indices"
	specifies the indices of which specific synsets of "first" are to be checked.
	*/
	public boolean isSynonymOf(String first, String second, int... indices)
	{
		Synset[] firstSynsetsAll = wordnet.getSynsets(first); //all synsets in wordnet of "first"
		Synset[] firstSynsetsDesired; //this array will hold only the desired synsets of "first" (specified by "indices")

		int i = 0;
		if (indices.length > 0) //if indices are specified
		{
			firstSynsetsDesired = new Synset[indices.length]; 
			for (int ind: indices)
			{
				firstSynsetsDesired[i] = firstSynsetsAll[ind]; 
				//this loop adds each of the specified synsets from firstSynsetsAll to the ith position in firstSynsetsDesired
				i++;
			}
		}
		else // if no indices are specified
			firstSynsetsDesired = firstSynsetsAll; //default to check all synsets

		for (Synset synset: firstSynsetsDesired)
		{
			String[] synonyms = synset.getWordForms();
			if (Arrays.asList(synonyms).contains(second)) //if second is one of the Strings in "synonyms"
				return true;
		}
		return false;
	}

	/**
	Tests if "first" is a hypernym of "second" by seeing if "first" is one of the
	hypernyms listed in WordNet of any VerbSynset containing "second".
	*/
	public boolean isHypernymOf(String first, String second)
	{
		Synset[] secondSynsets = wordnet.getSynsets(second, SynsetType.VERB);
		//we can only call getHypernyms() from VerbSynsets, not Synsets, so we have to do some casting
		VerbSynset[] secondVerbSynsets = Arrays.copyOf(secondSynsets, secondSynsets.length, VerbSynset[].class);
		//secondVerbSynsets contains all verb definitions of second

		for (VerbSynset defintion: secondVerbSynsets) 
		{
			VerbSynset[] hypernymSynsets = defintion.getHypernyms(); //hypernymSynsets contains all synsets containing hypernyms of second
			for (VerbSynset hypernymSynset: hypernymSynsets)
			{
				String[] wordForms = hypernymSynset.getWordForms(); //wordForms contains individual words that are hypernyms of second
				if (Arrays.asList(wordForms).contains(first)) // if first is one of the Strings in "wordForms"
					return true;
			}
		}
		return false;
	}

	/**
	Used by parseMotion() to update an ArrayList containing the allowed types of motion for a piece.
	Given a reference to a String "word" and an ArrayList<Direction> "motionTypes" holding certain Directions (representing the allowed
	types of motion for a piece), this method does the following:
	-Checks if "word" is a synonym of any of the directional words "diagonal"/"diagonally", "forward," "backward," "left," and "right." 
	  -If "word" is not a synonym of any of these directional words, the method does nothing else.
	  -If it is, the method then checks if whatever direction "word" entails has been added to "motionTypes" yet. 
	    -If it has not yet been added, the method adds that direction to "motionTypes."
	    -If it has already been added, the method does nothing else.
	*/
	public void addDirection(String word, ArrayList<Direction> motionTypes, int i) 
	{ //TODO: REMOVE i!!!
		/*ALL of the following specifications of indices (used when calling isSynonymOf()) are specific to the WordNet 3.0 database! 
		They must be changed for future versions of WordNet, as the indices of definitions change. */
		if (isSynonymOf("diagonal", word, 5, 6) || isSynonymOf("diagonally", word)) //5,6 are the indices in Wordnet 3.0 of the definitions of "diagonal" that denote direction
		{
			if (motionTypes.indexOf(Direction.DIAGONAL) < 0) //check to see if this type of motion has already been parsed
				motionTypes.add(Direction.DIAGONAL);
			System.out.println("Sentence " + i + ": Diagonal motion added."); //debugging
		}
		else if (isSynonymOf("forward", word, 3, 6, 7, 9, 11)) //3,6,7,9,11 are the indices in Wordnet 3.0 of the definitions of "forward" that denote direction
		{
			if (motionTypes.indexOf(Direction.FORWARD) < 0) 
				motionTypes.add(Direction.FORWARD);
			System.out.println("Sentence " + i + ": Forward motion added."); //debugging
		}
		else if (isSynonymOf("backward", word, 0, 2, 3)) //0,2,3 are the indices in Wordnet 3.0 of the definitions of "backward" that denote direction
		{
			if (motionTypes.indexOf(Direction.BACKWARD) < 0)
				motionTypes.add(Direction.BACKWARD);
			System.out.println("Sentence " + i + ": Backward motion added."); //debugging
		}
		else if (isSynonymOf("left", word, 19)) //19 is the index in Wordnet 3.0 of the definitions of "left" that denote direction
		{
			if (motionTypes.indexOf(Direction.LEFT) < 0)
				motionTypes.add(Direction.LEFT);
			System.out.println("Sentence " + i + ": Leftward motion added."); //debugging
		}
		else if (isSynonymOf("right", word, 12, 20)) //12,20 are the indices in Wordnet 3.0 of the definitions of "right" that denote direction
		{
			if (motionTypes.indexOf(Direction.RIGHT) < 0)
				motionTypes.add(Direction.RIGHT);
			System.out.println("Sentence " + i + ": Rightward motion added."); //debugging
		}

	}

	public enum Direction
	{
		FORWARD, BACKWARD, LEFT, RIGHT, DIAGONAL
	}



	
}