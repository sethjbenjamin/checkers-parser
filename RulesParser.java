import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import edu.stanford.nlp.dcoref.CorefChain;
import edu.stanford.nlp.dcoref.CorefCoreAnnotations;
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
	private Map<Integer, CorefChain> corefChains;
	private String[][] lemmas; //lemmas[i][j] holds the lemma of the jth word in the ith sentence of the text
	private String[][] partsOfSpeech; //partsOfSpeech[i][j] holds the POS of the jth word in the ith sentence of the text
	//WordNet 3.0 implementation using JAWS:
	private WordNetDatabase wordnet;

	private ArrayList<String> moveTypes;


	public RulesParser(String fileName)
	{
		this.fileName = fileName;

		// creates a StanfordCoreNLP object, with sentence splitting, POS tagging, lemmatization, parsing, NER, and coreference resolution
		Properties annotators = new Properties();
		annotators.put("annotators", "tokenize, ssplit, pos, lemma, parse, ner, dcoref");
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
			corefChains = annotation.get(CorefCoreAnnotations.CorefChainAnnotation.class);

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
					/* Lemmatization in CoreNLP is bad at dealing with capital letters and, occasionally, plural nouns.
					(For example: CoreNLP often thinks the lemma of "Checkers" is not "checker", but instead, "Checkers".)
					To compensate, we set every lemma to lower case and remove the final -s from any noun that ends in it;
					this is not a perfect solution, but it works for the purposes of parsing piece types. */
					String lemma = token.get(CoreAnnotations.LemmaAnnotation.class).toLowerCase(); //make all lemmas lower case
					String pos = token.get(CoreAnnotations.PartOfSpeechAnnotation.class);

					lemmas[i][j] = lemma;
					partsOfSpeech[i][j] = pos;

					//remove the final s from noun lemmas that end in it
					if (pos.charAt(0) == 'N' && lemma.charAt(lemma.length()-1) == 's')
						lemmas[i][j] =  lemma.substring(0, lemma.length()-1);

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

		System.out.println(determineReferent(16, 16)); //debugging

		for (Piece p: pieceTypes)
		{
			ArrayList<Integer> indices = determineMotionSentences(p);
			p.setMotionSentences(indices);
		}
		/* we have to iterate over pieceTypes two separate times, because motion sentences have to be determined for all piece 
		types before continuing, as determineMotionSentences() when passed a non-default type alters the indices of motion sentences
		for the default type. */
		for (Piece p: pieceTypes)
		{
			// TODO: this is kind of weird - parseMotion() could just call getMotionSentences(), 
			// and also could handle all the addMotionTypes() business 
			ArrayList<Direction> motionTypes = parseMotion(p, p.getMotionSentences());
			p.addMotionTypes(motionTypes);
			if (!p.isDefault())
			{
				Piece previous = p.getPreviousType();
				p.addMotionTypes(previous.getMotionTypes());
			}
		}
	}

	public ArrayList<String> parseMoveTypes()
	{
		/* This method works by iterating over all lemmas in a ruleset, counting the number of times any hyponym of 
		"move" appears in the ruleset, and choosing the n most frequent hyponyms to be the move types of the game.
		The following constant, NUM_MOVETYPES, specifies the value of n. There are two types of moves in checkers - 
		"moving" vs "jumping" - so this constant is set to 2.
		*/
		final int NUM_MOVETYPES = 2;

		/* The following hashmap associates String keys with Integer values. The String keys
		are every unique lemma of every hyponym of the verb "move" in the current ruleset. The Integer value associated with
		each of these lemmas is the number of times this lemma occurs in the ruleset.
		The n lemmas with highest Integer values are the n most frequent hyponyms of "move", and are therefore
		interpreted to be the n types of moves (where n = NUM_MOVETYPES.) */
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

		/* The following hashmap associates String keys with Integer values. The String keys
		are every unique lemma of every word in the current ruleset. The Integer value associated with
		each of these lemmas is the number of times this lemma occurs as:
		- a noun argument of any of the move types in moveTypes
		- a noun argument of any synonym of the verb "reach"
		- a noun argument of any synonym of the verb "become"
		The lemma with the highest frequency as argument of any of these predicates or as an appositive of "piece"
		is most likely a type of piece. */
		HashMap<String,Integer> arguments = new HashMap<String,Integer>();

		//iterate over all sentences
		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap sentence = sentences.get(i);
			
			for (String lemma: lemmas[i]) //iterate over all the lemmas of the current sentence;
			{
				if (!arguments.containsKey(lemma)) //if the lemma isn't in the hashmap,
					arguments.put(lemma, 0); //add each lemma to the hashmap
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
						arguments.put(lemma2, arguments.get(lemma2)+1); //increment value in hashmap
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
						arguments.put(lemma1, arguments.get(lemma1)+1); //increment value in hashmap
				}
			}
		}
		/* Now we need to find the lemma with the highest frequency in arguments. */
		int maxValue = -1;
		String mostFrequentArgument = "";
		for (Map.Entry<String,Integer> entry: arguments.entrySet())
		{
			if (maxValue < entry.getValue())
			{
				maxValue = entry.getValue();
				mostFrequentArgument = entry.getKey();
			}
			/*if (entry.getValue() > 0)
				System.out.println(entry.getKey() + ": " + entry.getValue()); //debugging */
		}

		System.out.println("First piece type parsed: " + mostFrequentArgument); //debugging
		Piece defaultPiece = new Piece(mostFrequentArgument);
		pieceTypes.add(defaultPiece);
		parseTransitionTypes(defaultPiece, pieceTypes);
		parsePreviousTypes(defaultPiece, pieceTypes);
		return pieceTypes;
		
	}

	public void parseTransitionTypes(Piece currentPiece, ArrayList<Piece> pieceTypes)
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

			String transitionPieceName = null; 

			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos2 = partsOfSpeech[i][index2-1];

				//The following checks if the sentence has any clause with name as its subject.
				//Normally one should only check lemma2; checking lemma1 as well can help compensate for coreNLP bugs.
				/*if (d.contains("nsubj") && (lemma2.equals(name) || lemma1.equals(name)))
					isNameSubject = true;*/

				/* The following checks if the sentence contains the predicate "become" with either name or a pronoun 
				that refers to name as its subject. */
				if (d.contains("nsubj") && lemma1.equals("become"))
				{
					if (lemma2.equals(name) || (pos2.equals("PRP") && determineReferent(i, index2).equals(name)))
						isNameSubject = true;
				}

				/* The following checks if the sentence contains the predicate "become", which takes a noun argument
				as either its direct object or its open clausal complement. */
				if (lemma1.equals("become") && (pos2.charAt(0) == 'N') && (d.contains("dobj") || d.contains("xcomp")))
				{
					isTransitionStatement = true; //if so, it is a transition statement
					transitionPieceName = lemma2;
				}
				//The following checks if the sentence is in the passive voice and has "make" as its predicate.
				if (d.contains("nsubjpass") && lemma1.equals("make"))
					isPassiveTransition = true; 
				/*The following checks if the sentence contains the predicate "make", which takes a noun argument
				as either its direct object or its open clausal complement. */
				if ((lemma1.equals("make")) && (pos2.charAt(0) == 'N') && (d.contains("dobj") || d.contains("xcomp")))
				{
					/* Setting isTransitionStatement equal to isPassiveTransition (as opposed to simply setting it to true) ensures that 
					isTransitionStatement is only set true when the sentence that is potentially a transition statement is in the passive voice. 
					This ensures that sentences like "the checker is made a king" are parsed as transition statements, 
					but sentences like "the checker makes a jump" are not. */
					isTransitionStatement = isPassiveTransition;
					transitionPieceName = lemma2;
				}
			}

			if (isNameSubject && isTransitionStatement)
			{
				System.out.print("New transition piece found: " + transitionPieceName + " in sentence " + i); //debugging
				System.out.println(" (previous type: " + name + ")"); //debugging
				Piece transitionPiece = new Piece(transitionPieceName, currentPiece); 
				//now we add the new type of piece to pieceTypes, but only if it hasn't already been added
				boolean isAlreadyAdded = false;
				for (Piece p: pieceTypes) //check all pieceTypes to see if any one is the same as newPiece
				{
					if (p.equals(transitionPiece))
					{
						isAlreadyAdded = true;
						if (p.getPreviousType() == null || !p.getPreviousType().equals(currentPiece)) //TODO: is this necessary
							p.setPreviousType(currentPiece);
						break; //don't need to check the rest
					}
				}
				if (!isAlreadyAdded) // if the piece hasn't already been added,
				{
					pieceTypes.add(transitionPiece); //add it
					parseTransitionTypes(transitionPiece, pieceTypes); // check for transition statements on the new piece
				}

			}
		}
	}

	public void parsePreviousTypes(Piece laterPiece, ArrayList<Piece> pieceTypes)
	{
		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap sentence = sentences.get(i);
			//dependencies for current sentence as a String[], each entry containing a single dependency String
			String[] dependencies = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");

			String name = laterPiece.getName();

			boolean isBecomeObjectName = false;
			boolean isBecomeSubjectNoun = false;
			boolean isBecomeSubjectPronoun = false;

			boolean isReachSubjectNoun = false;
			boolean isReachSubjectNumber = false;
			int numberIndex = -1;

			int indexOfBecomeSubj = -1;
			int indexOfBecomeObj = -1;

			String previousPieceName = null; 

			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos2 = partsOfSpeech[i][index2-1];

				/* The following checks if the sentence contains the predicate "become", specifically taking name
				as either its direct object or its open clausal complement.*/
				if ((d.contains("dobj") || d.contains("xcomp")) && lemma1.equals("become") && lemma2.equals(name))
				{
					isBecomeObjectName = true;
					indexOfBecomeObj = index1;
				}
				/* The following checks if the sentence contains the predicate "become", which takes either a noun or a pronoun 
				argument as its subject. If the subject is a noun, it is assumed to be a piece name and stored in previousPieceName.
				If the subject is a pronoun, we must determine what noun is its antecedent. */
				if ((d.contains("nsubj")) && lemma1.equals("become") && !lemma2.equals(name))
				{ 	
					if (pos2.charAt(0) == 'N')
					{
						isBecomeSubjectNoun = true;
						previousPieceName = lemma2;
					}
					else if (pos2.equals("PRP"))
						isBecomeSubjectPronoun = true;

					indexOfBecomeSubj = index1;
				}
				/*The following two if blocks are to determine, given that the subject of "become" is not a noun (ie, it is a pronoun),
				what its antecedent is. */
				/*The following if block checks:
				-if the subject of "become" is not a noun (if it is, that noun is probably the type of piece we want to parse, 
				 so we don't want to change previousPieceName at all)
				- if the sentence contains a predicate that is a synonym of "reach", and the current dependency lists its subject */
				if (!isBecomeSubjectNoun && d.contains("nsubj") && isSynonymOf("reach", lemma1))
				{
					//if so, we now check if the subject of reach is a noun; if so, it is probably the type of piece we want to parse.
					if (pos2.charAt(0) == 'N')
						previousPieceName = lemma2;
					/* if not, the subject of reach may be a number, as in the following construction: 
					"When one of your checkers reaches the opposite side of the board," etc. 
					If so, we have to store the subject's index, so we can find in another dependency what noun
					it modifies (as this noun is probably the type of piece we want to parse). */
					else if (pos2.equals("CD"))
					{
						isReachSubjectNumber = true; 
						numberIndex = index2;
					}
				}
				/* if the subject of "reach" is a number, we now have to determine what noun that number modifies.
				*/
				if (!isBecomeSubjectNoun && isReachSubjectNumber && d.contains("nmod") && index1 == numberIndex)
				{
					if (pos2.charAt(0) == 'N')
						previousPieceName = lemma2;
				}
			}

			if (isBecomeObjectName && 
				(isBecomeSubjectNoun || (isBecomeSubjectPronoun && previousPieceName != null)) 
				&& (indexOfBecomeSubj == indexOfBecomeObj))
			{
				System.out.print("New previous piece found: " + previousPieceName + " in sentence " + i); //debugging
				System.out.println(" (transition type: " + name + ")"); //debugging
				Piece previousPiece = new Piece(previousPieceName); 
				//we have to set the previousType of laterPiece to this newly parsed piece
				laterPiece.setPreviousType(previousPiece);
				//now we add the new type of piece to pieceTypes, but only if it hasn't already been added
				boolean isAlreadyAdded = false;
				for (Piece p: pieceTypes) //check all pieceTypes to see if any one is the same as newPiece
				{
					if (p.equals(previousPiece))
					{
						isAlreadyAdded = true;
						break; //don't need to check the rest
					}
				}
				if (!isAlreadyAdded) // if the piece hasn't already been added,
				{
					pieceTypes.add(previousPiece); //add it
					parsePreviousTypes(previousPiece, pieceTypes);
				}

			}
		}

	}




	/**
	Determines the indices of the sentences that describe the allowed directions of motion for a Piece p.
	*/
	public ArrayList<Integer> determineMotionSentences(Piece p)
	{
		String name = p.getName(); //the name of the Piece p

		//the following ArrayList will ultimately hold all the indices of sentences that describe the allowed motion of p
		ArrayList<Integer> indices = new ArrayList<Integer>(1);

		/* This method determines if a given sentence describes the motion of the Piece p as follows.
		A sentence is analyzed to determine if it has the following properties. If it has all of them, it is considered
		a motion sentence for p.
		- the name of the piece (henceforth referred to simply as "name") is a noun argument of any predicate in the sentence.
		  (the truth value of this property is stored by the boolean variable isNameArgument)
		- either name or a pronoun is an argument of a predicate that denotes one of the allowed types of motion in the game
		  (the truth value of this property is stored by the boolean variable isMoveArgument)
		- the statement does not contain a negation word (ie, it contains no negative dependencies - the truth value of this property
		  is stored by the boolean varaible isNegative)

		There are a few exceptions:
		- if p is a default type whose name is not "piece", "piece" can substitute for name in the previous properties and the sentence
		  will be considered a motion sentence.
		- if name is the modifier of a noun compound (eg. "king piece" where name == king), then the modified noun ("piece" in "king piece")
		  can also substitute for name in the previous properties and the sentence will be considered a motion sentence. (the boolean variable 
		  isNameCompounded is set to true if name is the modifier of a noun compound, and the String compoundedNoun stores the lemma of the
		  modified noun.)
		*/

		//iterate over all sentences
		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap currentSentence = sentences.get(i); //ith (current) sentence

			//dependencies of the current sentence
			String[] currentDependencies = currentSentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");

			boolean isNameArgument = false;
			boolean isNameCompounded = false;
			String compoundedNoun = null;
			int compoundedNounIndex = -1;
			boolean isMovePredicate = false;
			boolean isNegative = false;
			int index = -1;

			//iterate over all dependencies of the current sentence
			for (int j = 1; j < currentDependencies.length; j++)
			{
				String d = currentDependencies[j];
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos1 = partsOfSpeech[i][index1-1];
				String pos2 = partsOfSpeech[i][index2-1];

				/* The following if statement checks for negation words in the current sentence (by looking for negative dependencies).
				The method currently ignores sentences that have negation words, such that a sentence 
				like "Checkers cannot move backward." is not parsed. */
				if (d.contains("neg("))
					isNegative = true;

				/* The following if statement checks if name is a modifier in a noun compound. This matters because sometimes a noun compound,
				of which the current name of the piece is the modifying noun, is used instead of just name itself, for example:
				"King pieces can move in both directions, forward and backward." uses the noun compound "king pieces" instead of just saying
				"king". 
				If this is the case, we have to take the modified noun in the compound - in our above example, "pieces" - and store it in
				compoundedNoun; we do this as we will later have to check if compoundedNoun is an argument of a motion verb. (If it is,
				we have to treat this the same as if name itself were an argument.) */
				if (d.contains("compound(") && lemma2.toLowerCase().contains(name))
				{
					isNameCompounded = true;
					compoundedNoun = lemma1;
					compoundedNounIndex = index1;
				}

				/* The following if statement checks if name is either a subject, direct object, or open clausal complement in
				the current sentence. */
				/* the toLowerCase().contains(name) mess is in place of equals() because coreNLP can't figure out that the 
				lemma for "Pieces" is not "Pieces". */
				if ((d.contains("nsubj") || d.contains("xcomp") || d.contains("dobj")))
				{
					if (lemma2.equals(name)) //if name is the argument
						isNameArgument = true;

					/* In case of name being a modifier in a noun compound (that is, if isNameCompounded == true), we have to check
					if the noun it modifies is an argument as well, as this is equivalent to name itself being an argument. */
					else if (isNameCompounded && lemma2.equals(compoundedNoun) && index2 == compoundedNounIndex)
						isNameArgument = true;

					/* Sometimes the word "piece" is used to describe the motion of the default piece even when "piece" is not actually
					the name of the default piece, so when parsing the default piece, if its name is not "piece", we have to check for 
					motion sentences treating "piece" as the name as well. 
					The following checks if p is the default piece and p.name is not "piece", and if so, if 
					"piece" is the argument. */
					else if (p.isDefault() && !name.equals("piece") && lemma2.equals("piece"))
						isNameArgument = true;

				}
				/* The following if statement checks if the current sentence contains any of the move types previously parsed
				by the system as a predicate, and if so, if it either takes name or a pronoun as an argument. */
				if (moveTypes.contains(lemma1))
				{
					if ((pos2.equals("PRP") || lemma2.equals(name)))
					{
						isMovePredicate = true;
						index = i;
					}
					/* Same as above: in case of name being a modifier in a noun compound (that is, if isNameCompounded == true), 
					we have to check if the noun it modifies is an argument of a motion verb as well, as this is equivalent to name itself 
					being one. */
					else if (isNameCompounded && lemma2.equals(compoundedNoun) && index2 == compoundedNounIndex)
					{
						isMovePredicate = true;
						index = i;
					}
					/* Same as above: handling the default piece when its name is not "piece".
					The following checks if p is the default piece and p.name is not "piece", and if so, if 
					"piece" is the argument. */
					else if (p.isDefault() && !name.equals("piece") && lemma2.equals("piece"))
					{
						isMovePredicate = true;
						index = i;
					}
				}

				/* The following if statement checks p is the default piece, and if so, if the current sentence 
				contains the noun "move". This is because a statement like 
				"Only diagonal moves are allowed." 
				often is used to describe the motion of the default piece. */
				if (p.isDefault())
				{ 
					if ((lemma1.equals("move") && pos1.charAt(0) == 'N') || (lemma2.equals("move") && pos2.charAt(0) == 'N'))
					{
						//if the sentence contains the noun "move," add its index to indices
						if (!indices.contains(i))
						{
							indices.add(i);
							System.out.println("Motion sentence index for " + name + ": " + i + " (found from move (n))");
						}
					}
				}
			}

			/* We only want to add the current sentence's index to indices if:
			- name is an argument (that is, if isNameArgument == true)
			- one of the allowed movetypes is a predicate, which takes either name or a pronoun as an argument 
			  (that is, if isMovePredicate == true)
			- the sentence is not negative (that is, if isNegative == false)
			*/
			if (isNameArgument && isMovePredicate && !isNegative)
			{
				if (!indices.contains(index)) //also, we don't want to add multiple of the same index
				{
					indices.add(index);
					System.out.println("Motion sentence index for " + name + ": " + index);
				}
			}

			/* However, a lot of rulesets contain structures like:
			"The king is a more powerful piece. It can move forwards and backwards."
			In the second sentence, "it" refers to the piece type explicitly mentioned as a noun in the first sentence (king). 
			To effectively parse structures like this, we need to check also for move-type predicates with 
			pronoun arguments not only in the current sentence, but also in the following one. */
			if (i < sentences.size()-1) //if we're not on the last sentence,
			{
				CoreMap nextSentence = sentences.get(i+1); //i+1th (next) sentence

				//dependencies of the next sentence
				String[] nextDependencies = nextSentence.get(
					SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
					SemanticGraph.OutputFormat.LIST).split("\n");

				// iterate over all dependencies of the next sentence
				for (int j = 1; j < nextDependencies.length; j++)
				{
					String d = nextDependencies[j];
					int index1 = isolateIndexFromDependency(d,1);
					int index2 = isolateIndexFromDependency(d,2);
					String lemma1 = lemmas[i+1][index1-1];
					String pos2 = partsOfSpeech[i+1][index2-1];

					// The following if statement checks for negation words in the next sentence (by looking for negative dependencies).
					if (d.contains("neg("))
						isNegative = true;
					/* The following if statement checks if the next sentence contains any of the move types previously parsed
					by the system as a predicate, and if so, if it takes a pronoun as an argument. */
					if (moveTypes.contains(lemma1) && pos2.equals("PRP"))
					{
						isMovePredicate = true;
						index = i+1;
					}
				}

				/* We only want to add the next sentence's index to indices if:
				- name is an argument in the preceding (current) sentence (that is, if isNameArgument == true)
				- one of the allowed movetypes is a predicate in the next sentence, which takes either name or a pronoun as an argument 
			 	 (that is, if isMovePredicate == true)
				- neither the current sentence nor the next one is negative (that is, if isNegative == false)
				*/
				if (isNameArgument && isMovePredicate && !isNegative)
				{
					if (!indices.contains(index)) //also, we don't want to add multiple of the same index
					{
						indices.add(index);
						System.out.println("Motion sentence index for " + name + ": " + index);
					}
				}

			}	
		}

		/* This system, in order to handle anaphora, will assume a pronoun to refer to any of multiple nouns that precedes it; 
		there doesn't seem to be a good way to handle anaphoric ambiguity using dependencies, as it doesn't really depend on syntax. 
		This, however, causes problems, in sentences like: 
		"A king moves the same way as a regular checker, except he can move forward or backward."
		Such a sentence will be parsed as a motion sentence both for "checker" and for "king", as both are noun arguments that precede
		"he". This is bad - this sentence should only be parsed as a motion statement for kings.
		Thus, we check if p is not a default piece; if it isn't, we call its previous type, and remove all the indices of 
		motion sentences parsed for p from the indices of motion sentences parsed for the previous type. (It is likely that
		a sentence that mentions both types of pieces is only referring to the non-default one.) */
		if (!p.isDefault())
		{
			Piece previousType = p.getPreviousType();
			ArrayList<Integer> previousMotionSentences = previousType.getMotionSentences();
			for (Integer i: indices)
			{
				if (previousMotionSentences.remove(i))
					System.out.println("Sentence " + i + " removed from motion sentence indices of " + previousType.getName()); //debugging
			}
		}

		return indices;

	}

	public ArrayList<Direction> parseMotion(Piece p, ArrayList<Integer> indices)
	{
		String name = p.getName();
		ArrayList<Direction> motionTypes = new ArrayList<Direction>(1); 
		//ultimately, this ArrayList will hold all of the allowed types of motion explicitly described in the ruleset

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
					-one of the allowed move types
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
					if (moveTypes.contains(modified) || isSynonymOf("move", modified) || isSynonymOf("direction", modified) || isCoordinatingConjunction(modified))
					{
						//We now have to isolate the modifier as a substring.
						String modifier = isolateWordFromDependency(d,2); //the modifier is the second word in the dependency substring

						/*Now, we call addDirection() to check if "modifier" is a directional adverb or adjective, and if so, to
						add it to "motionTypes". */
						addDirection(modifier, motionTypes, i, name); //TODO: remove i!!!
					}
				}
				else if (d.contains("nmod:toward")) //check for a PP like "toward the opponent"
				{
					/*First we have to check if the word being modified by the PP is a hyponym of the verb "move," or a synonym of the nouns 
					"move" or "direction."
					Again, to determine what the modified word is, we have to isolate its index in the sentence as a substring of the 
					dependency string. */
					int modifiedIndex = isolateIndexFromDependency(d,1); //the modified word is the first word in the dependency substring

					//Now we determine the lemma of the modified word, and see if it is one of the move types
					String modified = lemmas[i][modifiedIndex-1]; //-1 because the sentence indices start from 1, not 0

					if (moveTypes.contains(modified) || isSynonymOf("direction", modified) || isSynonymOf("move", modified))
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
							System.out.println("Sentence " + i + ": Forward motion added for " + name + " as a modifying PP"); //debugging

						}
					}
				}
			}
		}
		return motionTypes;
	}

	public String determineReferent(int sentenceIndex, int wordIndex)
	{
		//sentence indices start at 0 for field "sentences" / for parameter sentenceIndex, but start at 1 for the corefchain
		// word indices start at 1
		//iterate over all CorefChains
		for (Map.Entry<Integer, CorefChain> entry: corefChains.entrySet())
		{
			//iterate over all CorefMentions in current CorefChain - all the phrases used to refer to a single referent
			for (CorefChain.CorefMention mention: entry.getValue().getMentionsInTextualOrder())
			{
				//We're only interested if mention (the full NP used to denote a referent) occurs in the sentence we want to look at
				//subtract 1 because the corefchain sentence indices start at 1 and ours start at 0
				if (mention.sentNum - 1 == sentenceIndex)
				{
					//tokens is a list of the words in the current sentence
					List<CoreLabel> tokens = sentences.get(mention.sentNum - 1).get(CoreAnnotations.TokensAnnotation.class);
					//if the head of the referring NP is the word we are trying to determine the referent of,
					if (mention.headIndex == wordIndex) // (we test this by comparing their indices in the sentence)
					{
						/*then we get the "representative mention" phrase - that is, what CoreNLP thinks is the
						R-expression that refers to the referent, as opposed to an anaphor - and return its head word */
						CorefChain.CorefMention referentMention = entry.getValue().getRepresentativeMention();
						CoreLabel referentWord = sentences.get(referentMention.sentNum - 1).get(
							CoreAnnotations.TokensAnnotation.class).get(referentMention.headIndex - 1);
						return referentWord.get(CoreAnnotations.TextAnnotation.class);
					}
				}
			}
		}
		return ""; //dummy value


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
	public void addDirection(String word, ArrayList<Direction> motionTypes, int i, String name) 
	{
		/*ALL of the following specifications of indices (used when calling isSynonymOf()) are specific to the WordNet 3.0 database! 
		They must be changed for future versions of WordNet, as the indices of definitions change. */

		 //5,6 are the indices in Wordnet 3.0 of the definitions of "diagonal" that denote direction
		if (isSynonymOf("diagonal", word, 5, 6) || isSynonymOf("diagonally", word))
		{
			if (motionTypes.indexOf(Direction.DIAGONAL) < 0) //check to see if this type of motion has already been parsed
				motionTypes.add(Direction.DIAGONAL);
			System.out.println("Sentence " + i + ": Diagonal motion added for " + name); //debugging
		}
		//3,6,7,9,11 are the indices in Wordnet 3.0 of the definitions of "forward" that denote direction
		else if (isSynonymOf("forward", word, 3, 6, 7, 9, 11))
		{
			if (motionTypes.indexOf(Direction.FORWARD) < 0) 
				motionTypes.add(Direction.FORWARD);
			System.out.println("Sentence " + i + ": Forward motion added for " + name); //debugging
		}
		//0,2,3 are the indices in Wordnet 3.0 of the definitions of "backward" that denote direction
		else if (isSynonymOf("backward", word, 0, 2, 3))
		{
			if (motionTypes.indexOf(Direction.BACKWARD) < 0)
				motionTypes.add(Direction.BACKWARD);
			System.out.println("Sentence " + i + ": Backward motion added for " + name); //debugging
		}
		//19 is the index in Wordnet 3.0 of the definitions of "left" that denote direction
		else if (isSynonymOf("left", word, 19))
		{
			if (motionTypes.indexOf(Direction.LEFT) < 0)
				motionTypes.add(Direction.LEFT);
			System.out.println("Sentence " + i + ": Leftward motion added for " + name); //debugging
		}
		//12,20 are the indices in Wordnet 3.0 of the definitions of "right" that denote direction
		else if (isSynonymOf("right", word, 12, 20))
		{
			if (motionTypes.indexOf(Direction.RIGHT) < 0)
				motionTypes.add(Direction.RIGHT);
			System.out.println("Sentence " + i + ": Rightward motion added."); //debugging
		}

	}
	
}