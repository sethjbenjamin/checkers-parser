import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
	private String[][] lemmas; //lemmas[i][j] holds the lemma of the jth word in the ith sentence of the text
	private String[][] partsOfSpeech; //partsOfSpeech[i][j] holds the POS of the jth word in the ith sentence of the text
	//WordNet 3.0 implementation using JAWS:
	private WordNetDatabase wordnet;

	private ArrayList<String> moveTypes;


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

			lemmas = new String[sentences.size()][];
			partsOfSpeech = new String[sentences.size()][];

			//iterate over all sentences
			for (int i = 0; i < sentences.size(); i++)
			{
				CoreMap sentence = sentences.get(i); //current sentence

				//initizalize lemmas[i] and partsOfSpeech[i] to be String arrays with length = number of tokens in current sentence
				lemmas[i] = new String[sentence.get(CoreAnnotations.TokensAnnotation.class).size()];
				partsOfSpeech[i] = new String[sentence.get(CoreAnnotations.TokensAnnotation.class).size()];

				int j = 0;
				for(CoreMap token: sentence.get(CoreAnnotations.TokensAnnotation.class)) //iterate over each word
				{
					lemmas[i][j] = token.get(CoreAnnotations.LemmaAnnotation.class);
					partsOfSpeech[i][j] = token.get(CoreAnnotations.PartOfSpeechAnnotation.class);
					j++;
				}


			}
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}

	}

	public void parse()
	{
		readFile();

		for (int i = 0; i < sentences.size(); i++) //debugging - prints all sentences w/ numbers
		{
			CoreMap current = sentences.get(i);
			System.out.println("" + i + ": " + current.get(CoreAnnotations.TextAnnotation.class));
		}
		
		moveTypes = parseMoveTypes();

		ArrayList<Piece> pieceTypes = parsePieceTypes();
		for (Piece p: pieceTypes)
		{
			ArrayList<Integer> indices = determineMotionSentences(p);
			ArrayList<Direction> motionTypes = parseMotion(indices);
			p.addMotionTypes(motionTypes);
			if (p.getPreviousType() != null)
			{
				Piece previous = p.getPreviousType();
				p.addMotionTypes(previous.getMotionTypes());
			}
		}
	}

	public ArrayList<String> parseMoveTypes()
	{
		/*
		This method works by iterating over all lemmas in a ruleset, counting the number of times any hyponym of 
		"move" appears in the ruleset, and choosing the n most frequent hyponyms to be the move types of the game.
		The following constant, NUM_MOVETYPES, specifies the value of n. There are two types of moves in checkers - 
		"moving" vs "jumping" - so this constant is set to 2.
		*/
		final int NUM_MOVETYPES = 2;

		ArrayList<String> parsedMoveTypes = new ArrayList<String>(NUM_MOVETYPES);

		HashMap<String,Integer> moveHyponyms = new HashMap<String,Integer>();
		for (int i = 0; i < sentences.size(); i++)
		{
			for (String lemma: lemmas[i])
			{
				if (isHypernymOf("move", lemma)) // only considering hyponyms of "move"
				{
					if (!moveHyponyms.containsKey(lemma)) // if the hyponym isn't in the hashmap,
						moveHyponyms.put(lemma, 0); // add it to the hashmap
					else // if the hyponym already is in the hashmap,
						moveHyponyms.put(lemma, moveHyponyms.get(lemma)+1); // increment its value in the hashmap
				}

			}
		}

		for (int i = NUM_MOVETYPES; i > 0; i--)
		{
			String mostFrequentHyponym = "";
			int maxValue = -1;
			for (Map.Entry<String,Integer> entry: moveHyponyms.entrySet())
			{
				String currentKey = entry.getKey();
				int currentValue = entry.getValue();
				if (maxValue < currentValue && !parsedMoveTypes.contains(currentKey))
				{
					maxValue = currentValue;
					mostFrequentHyponym = currentKey;
				}
			}
			parsedMoveTypes.add(mostFrequentHyponym);
			System.out.println("Move type parsed: " + mostFrequentHyponym);
		}
		return parsedMoveTypes;

	}

	public ArrayList<Piece> parsePieceTypes()
	{

		ArrayList<Piece> pieceTypes = new ArrayList<Piece>(1);

		/* The following hashmap associates String keys with Integer arguments. The String keys
		are every unique lemma of every word in the current ruleset. The Integer value associated with
		each of these lemmas is the number of times this lemma occurs as a noun argument of:
		- any of the move types in moveTypes
		- any synonym of the verb "reach"
		- any synonym of the verb "become"
		The lemma with the highest frequency as argument of any of these predicates is most likely a type of piece; 
		moreover, it is most likely the initial type of piece that all pieces start out as in a checkers variant. */
		HashMap<String,Integer> moveArguments = new HashMap<String,Integer>();

		//iterate over all sentences
		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap sentence = sentences.get(i);
			
			for (String lemma: lemmas[i]) //iterate over all the lemmas of the current sentence;
			{
				if (!moveArguments.containsKey(lemma)) //if the lemma isn't in the hashmap,
					moveArguments.put(lemma, 0); //add each lemma to the hashmap
			}

			//dependencies for current sentence as a String[], each entry containing a single dependency String
			String[] dependencies = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");

			//iterate over all dependencies for current sentence
			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j]; //current dependency
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos1 = partsOfSpeech[i][index1-1]; //POS of lemma1
				String pos2 = partsOfSpeech[i][index2-1]; //POS of lemma2

				/* The following if statement checks if lemma1 is:
				- any of the move types in moveTypes
				- any synonym of the verb "reach"
				- any synonym of the verb "become"
				 */
				if (moveTypes.contains(lemma1) || isSynonymOf("reach", lemma1) || isSynonymOf("become", lemma1))
				{	//if so, we inspect lemma2
					/* we only increment lemma2's value in the hashmap if:
					-it's a noun
					-it is not "player" or some synonym (0 is the index of the Wordnet 3.0 definition of "player" related to gameplay) 
					-it's not one of the moveTypes (phrases like "make a jump" are common enough that they usually get counted instead of
					piece types if this isn't checked) */
					if (pos2.charAt(0) == 'N' && !isSynonymOf("player", lemma2, 0) && !moveTypes.contains(lemma2))
						moveArguments.put(lemma2, moveArguments.get(lemma2)+1); //increment value in hashmap
				}
				/* if the previous if statement was false, the following if statement checks if lemma2 is:
				- any of the move types in moveTypes
				- any synonym of the verb "reach"
				- any synonym of the verb "become"
				 */
				else if (moveTypes.contains(lemma2) || isSynonymOf("reach", lemma2) || isSynonymOf("become", lemma2))
				{	//if so, we inspect lemma1
					/* we only increment lemma1's value in the hashmap if:
					-it's a noun
					-it is not "player" or some synonym (0 is the index of the Wordnet 3.0 definition of "player" related to gameplay) 
					-it's not one of the moveTypes (phrases like "make a jump" are common enough that they usually get counted instead of
					piece types if this isn't checked) */
					if (pos1.charAt(0) == 'N' && !isSynonymOf("player", lemma1, 0) && !moveTypes.contains(lemma1)) 
						moveArguments.put(lemma1, moveArguments.get(lemma1)+1); //increment value in hashmap
				}
			}
		}
		/* Now we need to find the lemma with the highest frequency in moveArguments. */
		int maxValue = -1;
		String mostFrequentArgument = "";
		for (Map.Entry<String,Integer> entry: moveArguments.entrySet())
		{
			if (maxValue < entry.getValue())
			{
				maxValue = entry.getValue();
				mostFrequentArgument = entry.getKey();
			}
		}

		System.out.println("Default piece type parsed: " + mostFrequentArgument); //debugging
		Piece defaultPiece = new Piece(mostFrequentArgument);
		pieceTypes.add(defaultPiece);
		parseTransitionStatements(defaultPiece, pieceTypes);
		
		return pieceTypes;
		
	}

	public void parseTransitionStatements(Piece currentPiece, ArrayList<Piece> pieceTypes)
	{
		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap sentence = sentences.get(i);
			//dependencies for current sentence as a String[], each entry containing a single dependency String
			String[] dependencies = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");


			String name = currentPiece.getName();
			boolean isNameSubject = false;
			boolean isTransitionStatement = false;
			boolean isPassiveTransition = false; //used for a construction like "a checker is made a king"

			String newPieceName = null; 

			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos2 = partsOfSpeech[i][index2-1];

				if (d.contains("nsubj") && (lemma2.equals(name) || lemma1.equals(name)))
					isNameSubject = true;
				if (lemma1.equals("become") && (pos2.charAt(0) == 'N') && (d.contains("dobj") || d.contains("xcomp")))
				{
					isTransitionStatement = true;
					newPieceName = lemma2;
				}
				if (d.contains("nsubjpass") && lemma1.equals("make")) //checks if the sentence is in the passive voice and has "make" as its predicate
					isPassiveTransition = true; 
				if ((lemma1.equals("make")) && (pos2.charAt(0) == 'N') && (d.contains("dobj") || d.contains("xcomp")))
				{
					/* Setting isTransitionStatement equal to isPassiveTransition (as opposed to simply setting it to true) ensures that 
					isTransitionStatement is only set true when the sentence that is potentially a transition statement is in the passive voice. 
					This ensures that sentences like "the checker is made a king" are parsed as transition statements, 
					but sentences like "the checker makes a jump" are not. */
					isTransitionStatement = isPassiveTransition;
					newPieceName = lemma2;
				}
			}

			if (isNameSubject && isTransitionStatement)
			{
				System.out.println("New piece found through transition: " + newPieceName + " in sentence " + i); //debugging
				Piece newPiece = new Piece(newPieceName, currentPiece); //we add the new type of piece to pieceTypes, but only if it hasn't already been added
				boolean isAlreadyAdded = false;
				for (Piece p: pieceTypes) //check all pieceTypes to see if any one is the same as newPiece
				{
					if (p.equals(newPiece))
					{
						isAlreadyAdded = true;
						break; //don't need to check the rest
					}
				}
				if (!isAlreadyAdded) // if the piece hasn't already been added,
				{
					pieceTypes.add(newPiece); //add it
					parseTransitionStatements(newPiece, pieceTypes); // check for transition statements on the new piece
				}
			}
		}
	}

	public ArrayList<Integer> determineMotionSentences(Piece p)
	{
		String name = p.getName();

		ArrayList<Integer> indices = new ArrayList<Integer>(1);

		for (int i = 0; i < sentences.size() - 1; i++)
		{
			CoreMap currentSentence = sentences.get(i);
			CoreMap nextSentence = sentences.get(i+1);

			String[] currentDependencies = currentSentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");
			String[] nextDependencies = nextSentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");

			boolean isNameArgument = false;
			boolean isMovePredicate = false;
			int index = -1;

			for (int j = 1; j < currentDependencies.length; j++)
			{
				String d = currentDependencies[j];
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos1 = partsOfSpeech[i][index1-1];
				String pos2 = partsOfSpeech[i][index2-1];

				// the toLowerCase mess is in place of equals() because coreNLP can't figure out that the lemma for "Pieces" is not "Pieces"
				if ((d.contains("nsubj") || d.contains("xcomp") || d.contains("dobj")) && lemma2.toLowerCase().contains(name))
					isNameArgument = true;	
				
				if (moveTypes.contains(lemma1) && (lemma2.equalsIgnoreCase("it") || lemma2.toLowerCase().contains(name)))
				{
					isMovePredicate = true;
					index = i;
				}
				/*
				TODO:
				-make this check for more than just nsubj(move,name)
				-implement a check for the very last sentence
				-test it on errythang
				*/
				if ((lemma1.equals("move") && pos1.charAt(0) == 'N') || (lemma2.equals("move") && pos2.charAt(0) == 'N'))
				{
					if (!indices.contains(i))
					{
						indices.add(i);
						System.out.println("Motion sentence index for " + name + ": " + i + " (found from move (n))");
					}
				}
			}
			for (int j = 1; j < nextDependencies.length; j++)
			{
				String d = nextDependencies[j];
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i+1][index1-1];
				String lemma2 = lemmas[i+1][index2-1];	
				
				if (moveTypes.contains(lemma1) && lemma2.equalsIgnoreCase("it"))
				{
					isMovePredicate = true;
					if (index == -1)
						index = i+1;
				}
			}
			if (isNameArgument && isMovePredicate)
			{
				if (!indices.contains(index))
					{
						indices.add(index);
						System.out.println("Motion sentence index for " + name + ": " + index);
					}
			}
		}
		return indices;

	}

	public ArrayList<Direction> parseMotion(ArrayList<Integer> indices)
	{
		ArrayList<Direction> motionTypes = new ArrayList<Direction>(1); 
		//ultimately, this ArrayList will hold all of the allowed types of motion explicitly described in the ruleset
		ArrayList<Direction> negatedDirections = new ArrayList<Direction>(1);
		/*ultimately, this ArrayList will old all of the types of motion described in a ruleset that are within the scope of a 
		negation word; the internal logic of parseMotion() does not distinguish between negated clauses and regular ones, so they must be 
		interpreted separately and then removed from motionTypes. */

		for (int i: indices)
		{
			CoreMap sentence = sentences.get(i); //the current sentence

			//dependencies for current sentence as a String[], each entry containing a single dependency String
			String[] dependencies = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");

			//iterate over all dependencies, searching for certain types
			for (String d: dependencies)
			{
				if (d.contains("advmod") || d.contains("amod")) // check for adverbs modifying verbs, or adjectives modifying nouns
				{
					/* We want to determine if the word being modified is any hyponym of the verb "move" being modified by a
					directional adverb, or any synonym of the nouns "move" and "direction" being modified by a directional adjective. 
					We first have to determine what the modified word's lemma is. 
					To do this, we isolate the word's index in the sentence as a substring of the dependency string. */
					int modifiedIndex = isolateIndexFromDependency(d, 1); //the word is the first word in the dependency substring

					//Now we determine the lemma of the modified word.
					String modified = lemmas[i][modifiedIndex-1]; //-1 because the sentence indices start from 1, not 0

					/*Now that we have the modified word's lemma, we determine if it is either:
					-a hypernym of "move (v)"
					-a synonym of "move (n)" or "direction (n)"
					-a coordinating conjunction (explained below)
					In a previous implementation, these dependencies (advmod and amod) were checked separately; checking for either at the same time
					as we are now actually allows not only for constructions in which directional adverbs modify verbs and directional adjectives 
					modify nouns, but also for those in which directional adverbs modify nouns and directional adjectives modify verbs. 
					This is grammatically impossible in Standard English, but Stanford CoreNLP often incorrectly analyzes sentences as 
					having such constructions, so checking for them gets better results.

					Additionally, Stanford CoreNLP has a strange bug in which a sentence such as the following: 
					"Kings move forward and backwards."
					will not produce the dependencies "advmod(move, forward)" and "advmod(move, backward)" as expected, but will instead 
					produce the following bizarre constructions: "advmod(move, and)", "advmod(and, forward)", "advmod(and, backward)". 
					Since our example sentence is one of the more common ways English language rulesets describe the motion of kings,
					we must also check if modified is a coordinating conjunction. */
					if (isHypernymOf("move", modified) || isSynonymOf("move", modified) || isSynonymOf("direction", modified) || isCoordinatingConjunction(modified))
					{
						//We now have to isolate the modifier as a substring.
						String modifier = isolateWordFromDependency(d,2); //the modifier is the second word in the dependency substring

						/*Now, we call addDirection() to check if "modifier" is a directional adverb or adjective, and if so, to
						add it to "motionTypes". */
						addDirection(modifier, motionTypes, i); //TODO: remove i!!!
					}
				}
				else if (d.contains("nmod:toward")) //check for a PP like "toward the opponent"
				{
					/*First we have to check if the word being modified by the PP is a hyponym of the verb "move," or a synonym of the nouns 
					"move" or "direction."
					Again, to determine what the modified word is, we have to isolate its index in the sentence as a substring of the 
					dependency string. */
					int modifiedIndex = isolateIndexFromDependency(d,1); //the modified word is the first word in the dependency substring

					//Now we determine the lemma of the modified word, and see if "move" is a hypernym of it
					String modified = lemmas[i][modifiedIndex-1]; //-1 because the sentence indices start from 1, not 0

					if (isHypernymOf("move", modified) || isSynonymOf("direction", modified) || isSynonymOf("move", modified))
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
					String negatedWord = negatedWordWithIndex.substring(0, negatedWordWithIndex.indexOf("-"));
					addDirection(negatedWord, negatedDirections, -1);
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

		for (int i = 0; i < dependencies.length; i++)
		{
			String d = dependencies[i];			
			if (isolateWordWithIndex(d,1).equals(negatedWordWithIndex))
			{
				String dependent = isolateWordFromDependency(d,2);
				String dependentWithIndex = isolateWordWithIndex(d,2);
				addDirection(dependent, negatedDirections, -1); //TODO: remove i

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
				try
				{
					isolatedIndex = Integer.parseInt(dependency.substring(startIndex, endIndex));
					return isolatedIndex;
				}
				catch (NumberFormatException e)
				{
					/*in my experience, coreNLP has a very rare bug where a dependency string will append an unpredictable
					number of apostrophes to the end of an index, producing a dependency substring that looks like, for example:
					dependency(word1-index1, word2-index2'')
					when this happens, dependency.substring(startIndex, endIndex) will return "index2''" which cannot be parsed by
					parseInt(). so, we decrement endIndex until dependency.charAt(endIndex-1) is not an apostrophe.
					*/
					while (dependency.charAt(endIndex-1) == '\'')
						endIndex--;
					isolatedIndex = Integer.parseInt(dependency.substring(startIndex, endIndex)); 
					return isolatedIndex;
				}
			case 2: 
				startIndex = dependency.lastIndexOf("-") + 1; //index in dependency string of the first digit of the second index
				endIndex = dependency.indexOf(")"); //index in dependency string of the right parenthesis immediately following the second index
				try
				{
					isolatedIndex = Integer.parseInt(dependency.substring(startIndex, endIndex));
					return isolatedIndex;
				}
				catch (NumberFormatException e)
				{
					while (dependency.charAt(endIndex-1) == '\'')
						endIndex--;
					isolatedIndex = Integer.parseInt(dependency.substring(startIndex, endIndex)); 
					return isolatedIndex;
				}
			default: //whichIndex can only equal 1 or 2, because a dependency string necessarily only contains 2 words (and therefore 2 indices)
				return -1; //error value - index in the sentence can never -1
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
	Tests if a word stored in a String is a coordinating conjunction. 
	Helpful to know because CoreNLP is bad at dealing with coordinating conjunctions.
	*/
	public boolean isCoordinatingConjunction(String word)
	{
		return word.equals("for") || word.equals("and") || word.equals("nor") || 
		word.equals("but") || word.equals("or") || word.equals("yet") || word.equals("so");
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
	
}