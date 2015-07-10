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
	private ArrayList<Piece> pieceTypes;


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
				for (CoreMap token: sentence.get(CoreAnnotations.TokensAnnotation.class)) //iterate over each word
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
		
		parseMoveTypes();
		parsePieceTypes();

		for (Piece p: pieceTypes)
		{ 
			ArrayList<Integer> indices = determineMotionSentences(p);
			ArrayList<Direction> motionTypes = parseMotion(p, indices);
			p.addMotionTypes(motionTypes);
		}

		for (int i = 0; i < pieceTypes.size(); i++)
		{
			Piece p = pieceTypes.get(i);
			if (p.getMotionTypes().size() == 0) // remove pieces with no parsed motion types (removes false positives)
			{
				System.out.println("Piece " + p.getName() + " removed");
				pieceTypes.remove(p);
				i--;
			}
			else if (!p.isDefault()) // add all motion types of a previous piece to its transition piece
			{
				Piece previous = p.getPreviousType();
				p.addMotionTypes(previous.getMotionTypes());
			}
		}
	}

	public void parseMoveTypes()
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
		moveTypes = new ArrayList<String>(NUM_MOVETYPES);

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
				if (maxValue < currentValue && !moveTypes.contains(currentKey))
				{
					maxValue = currentValue;
					mostFrequentHyponym = currentKey;
				}
			}
			moveTypes.add(mostFrequentHyponym);
			System.out.println("Move type parsed: " + mostFrequentHyponym);
		}
	}

	public void parsePieceTypes()
	{

		pieceTypes = new ArrayList<Piece>(1);

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
		Piece firstPiece = new Piece(mostFrequentArgument);
		pieceTypes.add(firstPiece);

		parseEquivalentTypes(firstPiece);
		parseTransitionTypes(firstPiece);
		parsePreviousTypes(firstPiece);
	}

	public void parseEquivalentTypes(Piece currentPiece)
	{
		String name = currentPiece.getName();

		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap sentence = sentences.get(i);
			//dependencies for current sentence as a String[], each entry containing a single dependency String
			String[] dependencies = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");

			boolean aclModifiesName = false;
			boolean aclModifiesOtherNoun = false;
			boolean aclHasEquivalentType = false;
			int participle = -1;
			String equivalentType = null;

			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos1 = partsOfSpeech[i][index1-1];
				String pos2 = partsOfSpeech[i][index2-1];

				// The following checks for noun appositive phrases modifying name.
				if (d.contains("appos(") && lemma1.equals(name) && pos2.charAt(0) == 'N')
				{
					/* Neither a move type nor any word referring to a player is ever the name of a piece; also,
					we don't want to simply add the same name as its own equivalent. */
					if (!moveTypes.contains(lemma2) && !isSynonymOf("player", lemma2, 0) && !lemma2.equals(name))
					{
						currentPiece.addEquivalentType(lemma2);
						System.out.println("Equivalent type parsed for " + name + " in sentence " + i + ": " + lemma2);
					}
				}
				/* The following checks for an adjectival clause modifying a noun, containing either 
				of the past participles "known" or "called" as its head word. */
				if (d.contains("acl(") && pos1.charAt(0) == 'N' && (lemma2.equals("know") || lemma2.equals("call")))
				{
					if (lemma1.equals(name)) //if the adjectival clause modifies name,
					{
						aclModifiesName = true;
						participle = index2; //get the index of the past participle 
					}
					/* If the adjectival clause is NOT modifying name, it may be modifying a noun that is equivalent to name.
					This is only possible if the noun being modified (lemma1) is not a move type, or a synonym of player (these
					cannot be equivalent types). */
					else if (!moveTypes.contains(lemma1) && !isSynonymOf("player", lemma1, 0))
					{
						aclModifiesOtherNoun = true;
						equivalentType = lemma1; //store the noun in equivalentType
						participle = index2; //get the index of the past participle
					}
				}
				/* The following if statement is intended to analyze the contents of the adjectival clause; we check whether
				index1 == participle in order to verify that we are in fact looking at it. 
				Otherwise, we check for either the past participle taking a noun argument as either a nominal modifier 
				(in the case of "name is known as x") or a direct object (in the case of "name is called x"). */
				if (d.contains("nmod") || (d.contains("dobj")) && pos2.charAt(0) == 'N' && index1 == participle)
				{
					/* If the adjectival clause is modifying a different noun, and its past participle takes name as its noun argument,
					this is a statement denoting an equivalent type to name. 
					(eg: name = man, and this sentence contains: "Single checkers, known as men, ..." -> equivalent type = checker) */
					if (aclModifiesOtherNoun && lemma2.equals(name))
						aclHasEquivalentType = true;
					/* If the adjectival clause is modifying name, and its past participle takes a different noun argument that is not
					a move type, name, or a synonym of player, this is a statement denoting an equivalent type to name.
					(eg: name = man, and this sentence contains: "A man, also called a checker, ..." -> equivalent type = checker) */
					else if (aclModifiesName && !moveTypes.contains(lemma1) && !isSynonymOf("player", lemma1, 0) && !lemma2.equals(name))
					{
						aclHasEquivalentType = true;
						equivalentType = lemma2;
					}
				}
			}

			if (aclHasEquivalentType)
			{
				currentPiece.addEquivalentType(equivalentType);
				System.out.println("Equivalent type parsed for " + name + " in sentence " + i + ": " + equivalentType);
			}
		}
	}

	public void parseTransitionTypes(Piece currentPiece)
	{
		String name = currentPiece.getName();

		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap sentence = sentences.get(i);
			//dependencies for current sentence as a String[], each entry containing a single dependency String
			String[] dependencies = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");

			boolean isNameSubject = false;
			boolean isTransitionSentence = false;
			boolean isPassiveTransition = false; //used for a construction like "a checker is made a king"
			boolean isPredicateNominative = false; //used for constructions like "the checker is now a king"

			String transitionPieceName = null;
			String antecedent = null;

			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos1 = partsOfSpeech[i][index1-1];
				String pos2 = partsOfSpeech[i][index2-1];

				/*The following checks for subject dependencies in the current sentence.. */
				if (d.contains("nsubj"))
				{
					/*The following checks if the sentence has any clause with any name of the currentPiece as its subject.
					Normally one should only check lemma2, as it is what CoreNLP determines to be the subject; additionally 
					checking lemma1, which is what CoreNLP determines to be the predicate, can help compensate for coreNLP bugs. 
					TODO: maybe the check for lemma1 should be removed, since we now check for predicate nominatives */
					if (currentPiece.isAnyName(lemma2) || currentPiece.isAnyName(lemma1))
						isNameSubject = true;
					/*The following checks uses CoreNLP's dcoref system to determine if the sentence has any clause with 
					an anaphor as its subject whose antecedent is any name of the currentPiece. */
					else if (pos2.equals("PRP"))
					{
						antecedent = determineAntecedent(i, index2);
						if (currentPiece.isAnyName(antecedent))
							isNameSubject = true;
					}
				}

				/* The following checks if the sentence contains the predicate "become", which takes a noun argument
				as either its direct object or its open clausal complement. If so, the noun argument is stored in transitionPieceName. */
				if (lemma1.equals("become") && (pos2.charAt(0) == 'N') && (d.contains("dobj") || d.contains("xcomp")))
				{
					isTransitionSentence = true; //if so, it is a transition sentence
					transitionPieceName = lemma2;
				}
				/* The following checks if the sentence contains the predicate "turn", which takes a prepositional phrase 
				with either "to" or "into" as its head. If so, the noun argument is stored in transitionPieceName. */
				if (lemma1.equals("turn") && (pos2.charAt(0) == 'N') && (d.contains("nmod:into") || d.contains("nmod:to")))
				{
					isTransitionSentence = true; //if so, it is a transition sentence
					transitionPieceName = lemma2;
				}
				//The following checks if the sentence is in the passive voice and has "make" as its predicate.
				if (d.contains("nsubjpass") && lemma1.equals("make"))
					isPassiveTransition = true; 
				/*The following checks if the sentence contains the predicate "make" in the passive voice, taking a noun argument
				as either its direct object or its open clausal complement. */
				if ((lemma1.equals("make")) && (pos2.charAt(0) == 'N') && (d.contains("dobj") || d.contains("xcomp")) && isPassiveTransition)
				{
					/* Checking isPassiveTransition in the if statement ensures that isTransitionSentence is only set 
					true when the sentence that is potentially a transition sentence is in the passive voice. 
					This ensures that sentences like "the checker is made a king" are parsed as transition sentences, 
					but sentences like "the checker makes a jump" are not. */
					isTransitionSentence = true;
					transitionPieceName = lemma2;
				}
				/* The following checks for a predicate nominative, like in the sentence "The checker is now a king.";
				these are detected easily, as CoreNLP ignores copula in its dependencies, so we simply check for
				a predicate that is a noun. */
				if (d.contains("nsubj") && pos1.charAt(0) == 'N')
				{
					isPredicateNominative = true;
					transitionPieceName = lemma1;
				}
				/* We only consider a predicate nominative as a transition sentence if it is modified by the adverb "now",
				so the following checks for that. */
				if (d.contains("advmod(") && pos1.charAt(0) == 'N' && lemma1.equals(transitionPieceName) && lemma2.equals("now") && isPredicateNominative)
					isTransitionSentence = true;
			}
			/* We only want to add this new piece as a transition piece if:
			- any of the names of the currentPiece, or a pronoun referring to it, is a subject in the sentence
			- any of the verbs "become", "turn into/to", and "make" (in the passive voice) is a predicate in the sentence,
			taking a noun as an argument (the name of the transition piece)
			- the name of the parsed transition piece is not the primary name of currentPiece
			(it's okay if it is one of the equivalent names, as those are prone to mistakes, which this method helps correct)

			Notably, this system never checks if either currentPiece or an anaphor referring to it is actually the subject of
			any of the transition verbs; just if they are the subject of ANY predicate. This is deliberate, to compensate for CoreNLP bugs;
			transition sentences are often parsed incorrectly. Thus, our imperfect system has the possibility of incorrectly interpreting 
			non-transition sentences as transition sentences. (eg "When the checker reaches the last row, the bear becomes a fish" would
			be interpreted as a sentence describing how checkers become fish). This, however, is uncommon in practice, as the false positives 
			would have to be pretty unnatural/irrelevant sentences. */
			if (isNameSubject && isTransitionSentence && !name.equals(transitionPieceName))
			{
				System.out.print("New transition piece found: " + transitionPieceName + " in sentence " + i); //debugging
				System.out.println(" (previous type: " + name + ")"); //debugging
				Piece transitionPiece = new Piece(transitionPieceName, currentPiece); 
				// we have to add the index of the transition sentence to the transitionSentences field of currentPiece
				currentPiece.addTransitionSentence(i, transitionPieceName);
				// now we add the new type of piece to pieceTypes, but only if it hasn't already been added
				boolean isAlreadyAdded = false;
				for (Piece p: pieceTypes) //check all pieceTypes to see if any one is the same as newPiece
				{
					/* If any of the already parsed pieces has the name of the new piece listed as an equivalent type, it's probably 
					by mistake. So, we check for that, and if so, remove the new piece name from the list of equivalents. */
					if (p.isEquivalentType(transitionPieceName))
					{
						p.removeEquivalentType(transitionPieceName);
						System.out.println(transitionPieceName + " removed from equivalent types of " + p.getName());
					}
					//check if we've already added the new piece to pieceTypes
					if (p.equals(transitionPiece))
					{
						isAlreadyAdded = true;
						if (p.getPreviousType() == null || !p.getPreviousType().equals(currentPiece))
							p.setPreviousType(currentPiece); 
						break; //don't need to check the rest
					}
				}
				if (!isAlreadyAdded) // if the piece hasn't already been added,
				{
					pieceTypes.add(transitionPiece); //add it
					parseEquivalentTypes(transitionPiece); // check for equivalent types of the new piece
					parseTransitionTypes(transitionPiece); // check for transition statements on the new piece
				}
			}
		}
	}

	public void parsePreviousTypes(Piece currentPiece)
	{
		String name = currentPiece.getName();

		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap sentence = sentences.get(i);
			//dependencies for current sentence as a String[], each entry containing a single dependency String
			String[] dependencies = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");

			boolean isObjectName = false;

			String previousPieceName = null; 
			String subjectOfReach = null;

			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos2 = partsOfSpeech[i][index2-1];

				/* The following checks if the sentence contains either of the predicates "become" or "make", specifically taking any 
				of the names of currentPiece as either its direct object or its open clausal complement. */
				if ((d.contains("dobj") || d.contains("xcomp")) && (lemma1.equals("become") || lemma1.equals("make")) && currentPiece.isAnyName(lemma2))
					isObjectName = true;
				/* The following checks if the sentence contains the predicate "turn", specifically taking a prepositional
				phrase headed by either "to" or "into" which takes any of the names of currentPiece as its object.*/
				else if ((d.contains("nmod:into") || d.contains("nmod:to")) && lemma1.equals("turn") && currentPiece.isAnyName(lemma2))
					isObjectName = true;

				/* The following checks if the sentence contains either of the predicates "become" or "turn", specifically 
				taking either a noun or a pronoun argument as its subject. 
				If the subject is a noun, it is assumed to be a piece name and stored in previousPieceName.
				If the subject is a pronoun, we call determineAntecedent() to determine what noun the pronoun refers to. 
				If this fails (as it often does, because CoreNLP), we search for the predicate "reach" or any synonym of it 
				in the sentence, and see what its subject is. This solution is not perfect, but a sufficient backup. */
				if ((d.contains("nsubj")) && (lemma1.equals("become") || lemma1.equals("turn")))
				{
					if (pos2.charAt(0) == 'N')
						previousPieceName = lemma2;
					else if (pos2.equals("PRP"))
					{
						String antecedent = determineAntecedent(i,index2);
						if (!antecedent.equals(""))
							previousPieceName = antecedent;
						else if (subjectOfReach != null)
							previousPieceName = subjectOfReach;
					}
				}
				/* The following checks if the sentence contains the predicate "make", necessarily in the passive voice, specifically 
				taking either a noun or a pronoun argument as its subject. 
				If the subject is a noun, it is assumed to be a piece name and stored in previousPieceName.
				If the subject is a pronoun, we call determineAntecedent() to determine what noun the pronoun refers to. 
				If this fails (as it often does, because CoreNLP), we search for the predicate "reach" or any synonym of it 
				in the sentence, and see what its subject is. This solution is not perfect, but a sufficient backup. */
				else if (d.contains("nsubjpass") && lemma1.equals("make"))
				{
					if (pos2.charAt(0) == 'N')
						previousPieceName = lemma2;
					else if (pos2.equals("PRP"))
					{
						String antecedent = determineAntecedent(i,index2);
						if (!antecedent.equals(""))
							previousPieceName = antecedent;
						else if (subjectOfReach != null)
							previousPieceName = subjectOfReach;
					}
				}
				/* The following checks if the sentence contains the predicate "reach"; if so, if its subject is a noun,
				it is stored in subjectOfReach. previousPieceName is set to this when the subject of become is
				a pronoun and no other antecedent can be determined using determineAntecedent() */
				if (d.contains("nsubj") && isSynonymOf("reach", lemma1))
				{
					if (pos2.charAt(0) == 'N')
						subjectOfReach = lemma2;
				}
			}
			/* We only want to add this new piece as a previous piece if:
			- the currentPiece is the object any of the verbs "become", "turn into/to", or "make" (in the passive voice)
			- anything was parsed as the subject of those verbs (either directly the subject, the CoreNLP-determined antecedent
			of an anaphor subject, or the subject of "reach" in another clause in the same sentence)
			- the name of the parsed previous piece is not the primary name of currentPiece 
			(it's okay if it is one of the equivalent names, as those are prone to mistakes, which this method helps correct) */
			if (isObjectName && previousPieceName != null && !name.equals(previousPieceName))
			{
				System.out.print("New previous piece found: " + previousPieceName + " in sentence " + i); //debugging
				System.out.println(" (transition type: " + name + ")"); //debugging
				Piece previousPiece = new Piece(previousPieceName); 
				//we have to set the previousType of currentPiece to this newly parsed piece
				currentPiece.setPreviousType(previousPiece);
				//now we add the new type of piece to pieceTypes, but only if it hasn't already been added
				boolean isAlreadyAdded = false;
				for (Piece p: pieceTypes) //check all pieceTypes to see if any one is the same as newPiece
				{
					/* If any of the already parsed pieces has the name of the new piece listed as an equivalent type, it's probably 
					by mistake. So, we check for that, and if so, remove the new piece name from the list of equivalents. */
					if (p.isEquivalentType(previousPieceName))
					{
						p.removeEquivalentType(previousPieceName);
						System.out.println(previousPieceName + " removed from equivalent types of " + p.getName());
					}
					//check if we've already added the new piece to pieceTypes
					if (p.equals(previousPiece))
					{
						isAlreadyAdded = true;
						//have to add this sentence as a transition sentence for the new previous type
						p.addTransitionSentence(i, name);
						break; //don't need to check the rest
					}
				}
				if (!isAlreadyAdded) // if the piece hasn't already been added,
				{
					pieceTypes.add(previousPiece); //add it
					previousPiece.addTransitionSentence(i, name); //add the parsed transition sentence to its list of transition sentences
					parseEquivalentTypes(previousPiece); // check for equivalent types of the new piece
					parsePreviousTypes(previousPiece); // check for previous types of the new piece
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
		- either any of the names of the piece or a pronoun that refers to any of those names is an argument 
		  (either subject or direct object) of a predicate that denotes one of the allowed types of motion in the game 
		  (the truth value of this property is stored by the boolean variable isMoveArgument)
		- the sentence is not a transition sentence for p (that is, a sentence that describes how p can turn into a different
		  type of piece) (this is determined by calling p.isTransitionSentence(i) where i is the index of the sentence)

		There are a few exceptions:
		- if the sentence contains a verb that describes motion in the game, with an anaphor as an argument, and the anaphor's antecedent
		  refers not to p but instead to p.previousType, the sentence is considered a motion sentence if the sentence containing
		  the anaphor's antecedent is a transition sentence for p.previousType. 
		  (eg: "A checker becomes a king upon reaching the king row. It then can move backward and forward." "it" refers to "checker",
		  but the sentence containing "checker" is a transition sentence for "checker"; therefore, the second sentence
		  is considered a motion sentence for king.)
		- if name is the modifier of a noun compound (eg. "king piece" where name == king), then the modified noun ("piece" in "king piece")
		  can also substitute for name in the previous properties and the sentence will be considered a motion sentence. (the boolean variable 
		  isNameCompounded is set to true if name is the modifier of a noun compound, and the String compoundedNoun stores the lemma of the
		  modified noun.)
		*/

		//iterate over all sentences
		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap sentence = sentences.get(i); //ith (current) sentence

			//dependencies of the current sentence
			String[] dependencies = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");

			boolean isMovePredicate = false;
			int index = -1;

			boolean isNameCompounded = false;
			String compoundedNoun = null;
			int compoundedNounIndex = -1;

			//iterate over all dependencies of the current sentence
			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos1 = partsOfSpeech[i][index1-1];
				String pos2 = partsOfSpeech[i][index2-1];

				/* The following if statement checks if one of the names of p is a modifier in a noun compound. This matters because sometimes 
				a noun compound, of which the current name of the piece is the modifying noun, is used instead of just name itself, for example:
				"King pieces can move in both directions, forward and backward." uses the noun compound "king pieces" instead of just saying
				"king". 
				If this is the case, we have to take the modified noun in the compound - in our above example, "pieces" - and store it in
				compoundedNoun; we do this as we will later have to check if compoundedNoun is an argument of a motion verb. (If it is,
				we have to treat this the same as if the piece's name itself were an argument.) */
				if (d.contains("compound(") && p.isAnyName(lemma2))
				{
					isNameCompounded = true;
					compoundedNoun = lemma1;
					compoundedNounIndex = index1;
				}

				/* The following if statement checks if the current sentence contains any of the move types previously parsed
				by the system as a predicate, and if so, if it either takes name or a pronoun as a subject or direct object. */
				if (moveTypes.contains(lemma1) && (d.contains("dobj") || d.contains("nsubj")))
				{
					Piece previousType = p.getPreviousType();
					//if the argument of the motion predicate is any of the names of p, this is probably a motion sentence
					if (p.isAnyName(lemma2))
					{
						isMovePredicate = true;
						index = i;
					}
					//the following checks if the predicate's argument is a pronoun
					else if (pos2.equals("PRP"))
					{
						String antecedent = determineAntecedent(i, index2); //antecedent of the pronoun

						/* We consider this a motion sentence if the pronoun's antecedent is any of the names
						of p, and if the sentence contaning the antecedent (assumed to be either the current
						sentence or the previous one) is not a transition statement of p. */
						if (p.isAnyName(antecedent) && !p.isTransitionSentence(i) && !p.isTransitionSentence(i-1))
						{
							isMovePredicate = true;
							index = i;
						}
						/* In the case of the following sentence:
						"When a checker reaches the row on the farthest edge from the player, it becomes a king and may 
						then move and jump both diagonally forward and backward, following the same rules as above."
						the antecedent of "it" is grammatically "checker"; however, this sentence describes the motion of
						kings, not checkers (that is, it describes the motion of checkers after they become kings.)
						Thus, when the antecedent of a pronoun refers not to p (the piece we are currently parsing the motion of),
						but instead to p's previous type, we have to check if the sentence containing the antecedent (assumed to be
						either the current sentence or the previous one) is a transition sentence describing how previousType becomes p; 
						if it is, we must still consider the current sentence a motion sentence for p. */
						else if (previousType != null && 
							previousType.isAnyName(antecedent) && 
							(previousType.isTransitionSentence(i, name) || previousType.isTransitionSentence(i-1, name)))
						{
							isMovePredicate = true;
							index = i; 
						}
					}
					/* Same as above: in case of name being a modifier in a noun compound (that is, if isNameCompounded == true), 
					we have to check if the noun it modifies is an argument of a motion verb as well, as this is equivalent to name itself 
					being one. */
					else if (isNameCompounded && lemma2.equals(compoundedNoun) && index2 == compoundedNounIndex)
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
			- one of the allowed movetypes is a predicate, which takes either name or a pronoun referring to it as an argument 
			  (that is, if isMovePredicate == true)
			- the sentence is not a transition sentence for p
			*/
			if (isMovePredicate && !p.isTransitionSentence(i))
			{
				if (!indices.contains(index)) //also, we don't want to add multiple of the same index
				{
					indices.add(index);
					System.out.println("Motion sentence index for " + name + ": " + index);
				}
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
			ArrayList<Integer> negatedWords = new ArrayList<Integer>(1);
			/*this ArrayList will hold negated words in the current sentence (those modified by a negation word); 
			if a direction word or the word it modifies is in this list, it will not be added to motionTypes */

			CoreMap sentence = sentences.get(i); //the current sentence

			//dependencies for current sentence as a String[], each entry containing a single dependency String
			String[] dependencies = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");

			//iterate over all dependencies, searching for certain types
			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = isolateIndexFromDependency(d, 1); 
				int index2 = isolateIndexFromDependency(d, 2); 
				String lemma1 = lemmas[i][index1-1]; 
				String lemma2 = lemmas[i][index2-1]; 

				if (d.contains("neg("))
					negatedWords.add(index1);
				else if (d.contains("dep(") && lemma1.equals("not"))
					negatedWords.add(index2);

				if (d.contains("advmod(") || d.contains("amod(")) // check for adverbs modifying verbs, or adjectives modifying nouns
				{
					/* We want to determine if the word being modified is one of the allowed move types being modified by a
					directional adverb, or any synonym of the nouns "move" and "direction" being modified by a directional adjective. */

					/* The following determines if lemma1 (the modified word) is:
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
					if (moveTypes.contains(lemma1) || isSynonymOf("move", lemma1) || isSynonymOf("direction", lemma1) || isCoordinatingConjunction(lemma1))
					{
						/*Now, we call addDirection() to check if lemma2, the modifier, is a directional adverb or adjective, and if so, to
						add it to motionTypes. */
						if (!negatedWords.contains(index1) && !negatedWords.contains(index2))
							addDirection(lemma2, motionTypes, i, name); //TODO: remove i!!!
					}
				}
				else if (d.contains("nmod:toward")) //check for a PP like "toward the opponent"
				{
					/* The following checks if the word being modified by the PP is one of the allowed move types or a synonym of the nouns 
					"move" or "direction." */
					if (moveTypes.contains(lemma1) || isSynonymOf("direction", lemma1) || isSynonymOf("move", lemma1))
					{
						/*At this point, we have determined that the phrase we are looking at is a PP of the form "toward/towards [DP]" 
						modifying a motion predicate, or either of the nouns "move" or "direction". 
						Now we have to determine the object of the proposition, and what direction of motion this object implies. 
						For now, since it is the only such phrase that occurs in our 10 sample rulesets, the only object of "toward"/"towards"
						that we will consider is the noun "opponent." This entails forward motion: "checkers can only move toward the opponent" 
						is equivalent to "checkers can only move forward". */
						if (isSynonymOf("opponent", lemma2)) //the object of the preposition is the second word in the dependency
						{
							if (motionTypes.indexOf(Direction.FORWARD) < 0) //TODO: maybe add a negation check
								motionTypes.add(Direction.FORWARD);
							System.out.println("Sentence " + i + ": Forward motion added for " + name + " as a modifying PP"); //debugging

						}
					}
				}
			}
		}
		return motionTypes;
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

	/**
	Uses CoreNLP's dcoref system to determine the antecedent of an anaphor. Necessarily returns a noun - either returns the head word
	of the NP antecedent, or, if the antecedent is not an NP, returns the first noun in the phrase.
	Returns an empty string if CoreNLP is unable to determine an antecedent for the word.
	*/
	public String determineAntecedent(int sentenceIndex, int wordIndex)
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
					//if the head of the referring NP is the anaphor we are trying to determine the antecedent of,
					if (mention.headIndex == wordIndex) // (we test this by comparing their indices in the sentence)
					{
						/*then we get the "representative mention" phrase - that is, what CoreNLP thinks is the
						R-expression that refers to the antecedent, as opposed to an anaphor - and return the lemma of its head word */
						CorefChain.CorefMention antecedentMention = entry.getValue().getRepresentativeMention();
						//if the head word of the representative mention phrase is a noun, return it
						if (partsOfSpeech[antecedentMention.sentNum-1][antecedentMention.headIndex-1].charAt(0) == 'N')
							return lemmas[antecedentMention.sentNum-1][antecedentMention.headIndex-1];

						//if it's not, go sequentially through until a noun is found
						else
						{
							List<CoreLabel> antecedentTokens = sentences.get(antecedentMention.sentNum - 1).get(
								CoreAnnotations.TokensAnnotation.class);
							// iterate over the entire "representative mention" phrase by iterating from indices startIndex-1 to endIndex-1
							for (int i = antecedentMention.startIndex - 1; i < antecedentMention.endIndex - 1; i++)
							{
								//TODO: the implementation is great but the logic's not - 
								// maybe something better than just returning the first noun in the phrase?
								if (partsOfSpeech[antecedentMention.sentNum-1][i].charAt(0) == 'N')
									return lemmas[antecedentMention.sentNum-1][i];
							}
						}

					}
				}
			}
		}
		return ""; //dummy value
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
	
}