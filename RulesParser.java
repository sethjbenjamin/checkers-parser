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
import edu.stanford.nlp.trees.*;
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
		System.out.println(motionTypes.size());
		
		
	}

	public ArrayList<Direction> parseMotion()
	{
		ArrayList<Direction> motionTypes = new ArrayList<Direction>(1); 
		//ultimately, this ArrayList will hold all of the allowed types of motion explicitly described in the ruleset
		ArrayList<Direction> negatedDirections = new ArrayList<Direction>(1);
		/*ultimately, this ArrayList will old all of the types of motion described in a ruleset that are within the scope of a 
		negation word; the internal logic of parseMotion() does not distinguish between negated clauses and regular ones, so they must be 
		interpreted separately and then removed from motionTypes. */

		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap sentence = sentences.get(i); //the current sentence

			//We create an ArrayList of the lemmas of each word in "sentence", stored as Strings.
			ArrayList<String> lemmas = new ArrayList<String>(1);
			for(CoreMap token: sentence.get(CoreAnnotations.TokensAnnotation.class)) //iterate over each word
				lemmas.add(token.get(CoreAnnotations.LemmaAnnotation.class)); //add its lemma to the ArrayList

			//Now we create an array of the syntactic dependencies of "sentence"
			//the next line simply initializes "dependeciesString" with a String containing all the dependencies of "sentence"
			String dependenciesString = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(SemanticGraph.OutputFormat.LIST);
			String[] dependencies = dependenciesString.split("\n"); //split the String into an array, one dependency per index in the array

			//iterate over all dependencies, searching for certain types
			for (String d: dependencies)
			{
				if (d.contains("advmod")) // check for adverbs modifying verbs
				{
					/* We want to determine if the verb being modified by an adverb is any hyponym of the verb "move", so we first 
					have to determine what the verb's lemma is. To do this, we isolate the verb's index in the sentence as a substring of the 
					dependency string. */
					int verbIndex = isolateIndexFromDependency(d, 1); //the verb is the first word in the dependency substring

					//Now we determine the lemma of the verb, and see if "move" is a hypernym of it.
					String verbLemma = lemmas.get(verbIndex-1); //-1 because the sentence indices start from 1, not 0

					/* Stanford CoreNLP has a strange bug in which a sentence such as the following: "Kings move forward and backwards."
					will not produce the dependencies "advmod(move, forward)" and "advmod(move, backward)" as expected, but will instead 
					produce the following bizarre constructions: "advmod(move, and)", "advmod(and, forward)", "advmod(and, backward)". 
					So, in addition to checking if verbLemma is a hyponym of "move", we have to check if it's a coordinating conjunction, too,
					since our example sentence is one of the more common ways English language rulesets describe the motion of kings. */
					if (isHypernymOf("move", verbLemma) || verbLemma.equals("or") || (verbLemma.equals("and")))
					{
						//We now have to isolate the modifying adverb as a substring.
						String adverb = isolateWordFromDependency(d,2); //the adverb is the second word in the dependency substring

						/*Now, we call addDirection() to check if "adverb" is a directional adverb, and if so, to
						add it to "motionTypes". */
						addDirection(adverb, motionTypes, i); //TODO: remove i!!!
					}
				}
				else if (d.contains("amod")) //check for adjectives modifying nouns
				{
					/* We want to determine if the noun being modified by an adjective is any synonym of the lexemes 
					"move (n)" or "direction (n)", so we first have to determine what the noun's lemma is. To do this, 
					we isolate the noun's index in the sentence as a substring of the dependency string. */
					int nounIndex = isolateIndexFromDependency(d, 1); //the noun is the first word in the dependency substring

					//Now we determine the lemma of the noun, and see if it is a synonym of "move" or "direction."
					String nounLemma = lemmas.get(nounIndex-1); //-1 because the sentence indices start from 1, not 0

					if (isSynonymOf("move", nounLemma) || isSynonymOf("direction", nounLemma))
					{
						//We now isolate the modifiying adjective as a substring.
						String adjective = isolateWordFromDependency(d, 2); //the adjective is the second word in the dependency substring
						
						/*Now, we call addDirection() to check if "adjective" is a directional adjective, and if so, to
						add it to "motionTypes". */
						addDirection(adjective, motionTypes, i); //TODO: remove i!!!
					}

				}
				else if (d.contains("nmod:toward")) //check for a PP like "toward the opponent"
				{
					/*First we have to check if the word being modified by the PP is a hyponym of the verb "move," or a synonym of the nouns 
					"move" or "direction."
					Again, to determine what the modified word is, we have to isolate its index in the sentence as a substring of the 
					dependency string. */
					int wordIndex = isolateIndexFromDependency(d,1); //the modified word is the first word in the dependency substring

					//Now we determine the lemma of the modified word, and see if "move" is a hypernym of it
					String lemma = lemmas.get(wordIndex-1); //-1 because the sentence indices start from 1, not 0 

					if (isHypernymOf("move", lemma) || isSynonymOf("direction", lemma) || isSynonymOf("move", lemma))
					{
						/*At this point, we have determined that the phrase we are looking at is a PP of the form "toward/towards [DP]" 
						modifying some hyponym of "move." Now we have to determine the object of the proposition, and what direction of motion
						this object implies. 
						For now, since it is the only such phrase that occurs in our 10 sample rulesets, the only object of "toward"/"towards"
						that we will consider is the noun "opponent." This entails forward motion: "checkers can only move toward the opponent" 
						is equivalent to "checkers can only move forward". */
						String noun = isolateWordFromDependency(d,2); //the verb is the second word in the dependency substring
						if (isSynonymOf("opponent", noun))
						{
							if (motionTypes.indexOf(Direction.FORWARD) < 0)
								motionTypes.add(Direction.FORWARD);
							System.out.println("Sentence " + i + ": Forward motion added, as a modifying PP."); //debugging

						}
					}
				}
				else if (d.contains("neg"))
				{
					String negatedWordWithIndex = isolateWordWithIndex(d,1);
					parseNegatedDirections(dependencies, negatedWordWithIndex, negatedDirections);
				}
			}
		}
		for (Direction d: negatedDirections)
			motionTypes.remove(d);
		return motionTypes;
	}

	public void parseNegatedDirections(String[] dependencies, String negatedWordWithIndex, ArrayList<Direction> negatedDirections)
	{
		/*PROBLEM!!! TODO! This method so far just nixes every single word touched by a negation word. Doesn't check for any of the 
		stuff parseMotion() checks for. Might be a problem eventually. */
		String negatedWord = negatedWordWithIndex.substring(0, negatedWordWithIndex.indexOf("-"));
		addDirection(negatedWord, negatedDirections, -1);
		for (int i = 0; i < dependencies.length; i++)
		{
			String d = dependencies[i];			
			if (isolateWordWithIndex(d,1).equals(negatedWordWithIndex))
			{
				/*String word1 = isolateWordFromDependency(d, 1);
				String word2 = isolateWordFromDependency(d, 2);
				String otherWord, otherWordWithIndex;
				if (word1.equals(negatedWord))
				{
					otherWord = word2;
					otherWordWithIndex = otherWord + "-" + isolateIndexFromDependency(d,2); 
					//must include index in case the same word appears twice in the sentence, but only one is within the scope of a negation word
				}
				else 
				{
					otherWord = word1;
					wordWithIndex = otherWord + "-" + isolateIndexFromDependency(d,1);
				}*/
				String dependent = isolateWordFromDependency(d,2);
				String dependentWithIndex = isolateWordWithIndex(d,2);
				addDirection(dependent, negatedDirections, -1); //TODO: remove i
				/*if (d.contains("conj:") || d.contains("nmod") || d.contains("dobj"))
				{
					String[] dependenciesClone = dependencies.clone();
					dependenciesClone[i] = "";
					parseNegatedDirections(dependenciesClone, otherWordWithIndex, negatedDirections);
				}*/
				String[] truncatedDependencies = new String[dependencies.length-1];
				for (int j = 0; j < dependencies.length - 1; j++)
				{
					if (j > i)
						truncatedDependencies[j] = dependencies[j];
					else
						truncatedDependencies[j] = dependencies[j+1];
				}
				//after this loop, truncatedDependencies contains all the values of dependencies except d
				parseNegatedDirections(truncatedDependencies, dependentWithIndex, negatedDirections);
			}
		}		
	}

	/**
	Given a dependency string of the form: "dependency(word1-index1, word2-index2)",
	this method isolates and returns either "word1" or "word2" as a String, depending on if whichWord equals 1 or 2 respectively.
	Returns null if whichWord does not equal 1 or 2.
	*/
	public String isolateWordFromDependency(String dependency, int whichWord)
	{
		int startIndex, endIndex;
		String word;

		switch (whichWord)
		{
			case 1:
				startIndex = dependency.indexOf("(") + 1; //index in dependency string of the first character of the first word
				endIndex = dependency.lastIndexOf("-", dependency.indexOf(",")); //index in dependency string of hyphen immediately following first word
				word = dependency.substring(startIndex, endIndex);
				return word;
			case 2: 
				startIndex = dependency.indexOf(" ") + 1; //index in dependency string of the first character of the second word
				endIndex = dependency.lastIndexOf("-"); //index in dependency string of hyphen immediately following second word
				word = dependency.substring(startIndex, endIndex);
				return word;
			default: //whichWord can only equal 1 or 2, because a dependency string necessarily only contains two words
				return null;
		}
	}

	/**
	Given a dependency string of the form: "dependency(word1-index1, word2-index2)",
	this method isolates and returns either "index1" or "index2", depending on if whichIndex equals 1 or 2 respectively.
	Returns -1 if whichWord does not equal 1 or 2.
	*/
	public int isolateIndexFromDependency(String dependency, int whichIndex)
	{
		int startIndex, endIndex;
		int isolatedIndex;

		switch (whichIndex)
		{
			case 1:
				endIndex = dependency.indexOf(","); //index in dependecy string of the comma immediately following the first index
				startIndex = dependency.lastIndexOf("-", endIndex) + 1; //index in dependency string of the first digit of of the first index
				isolatedIndex = Integer.parseInt(dependency.substring(startIndex, endIndex));
				return isolatedIndex;
			case 2: 
				startIndex = dependency.lastIndexOf("-") + 1; //index in dependency string of the first digit of the second index
				endIndex = dependency.indexOf(")"); //index in dependency string of the right parenthesis immediately following the second index
				isolatedIndex = Integer.parseInt(dependency.substring(startIndex, endIndex));
				return isolatedIndex;
			default: //whichIndex can only equal 1 or 2, because a dependency string necessarily only contains 2 words (and therefore 2 indices)
				return -1;
		}
	}

	/**
	Given a dependency string of the form: "dependency(word1-index1, word2-index2)",
	this method isolates and returns either "word1-index1" or "word2-index2", depending on if whichWord equals 1 or 2 respectively.
	Returns null if whichWord does not equal 1 or 2.
	*/
	public String isolateWordWithIndex(String dependency, int whichWord)
	{
		if (whichWord == 1 || whichWord == 2)
			return isolateWordFromDependency(dependency, whichWord) + "-" + isolateIndexFromDependency(dependency, whichWord);
		else
			return null;
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