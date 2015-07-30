import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.util.*;


public class PieceParser
{
	private RulesParser parent;
	private List<CoreMap> sentences;
	private String[][] lemmas; //lemmas[i][j] holds the lemma of the jth word in the ith sentence of the text
	private String[][] partsOfSpeech; //partsOfSpeech[i][j] holds the POS of the jth word in the ith sentence of the text

	private String[][] transitionZones; //2d array representing board, initially null - parseTransitionZones will edit with proper zones

	private ArrayList<String> moveTypes;
	private ArrayList<Piece> pieceTypes;

	public PieceParser(RulesParser parent, List<CoreMap> sentences, String[][] lemmas, String[][] partsOfSpeech, String[][] transitionZones)
	{
		this.parent = parent;
		this.sentences = sentences;
		this.lemmas = lemmas;
		this.partsOfSpeech = partsOfSpeech;
		this.transitionZones = transitionZones;
	}

	public void parsePieces()
	{
		parseMoveTypes();
		parsePieceTypes();

		MotionParser motionParser = new MotionParser(this.parent, this.sentences, this.lemmas, this.partsOfSpeech,
			this.moveTypes, this.pieceTypes);
		motionParser.parseAll();

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

	public ArrayList<String> getMoveTypes()
	{
		return moveTypes;
	}

	public ArrayList<Piece> getPieceTypes()
	{
		return pieceTypes;
	}

	public String[][] getTransitionZones()
	{
		return transitionZones;
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
				if (RulesParser.isHypernymOf("move", lemma)) // only considering hyponyms of "move"
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
				int index1 = RulesParser.isolateIndexFromDependency(d,1);
				int index2 = RulesParser.isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos1 = partsOfSpeech[i][index1-1]; //POS of lemma1
				String pos2 = partsOfSpeech[i][index2-1]; //POS of lemma2

				/* The following if statement checks if lemma1 is:
				- any of the move types in moveTypes
				- any synonym of the verb "reach"
				- any synonym of the verb "become"
				 */
				if (moveTypes.contains(lemma1) || RulesParser.isSynonymOf("reach", lemma1) || RulesParser.isSynonymOf("become", lemma1))
				{	//if so, we inspect lemma2
					/* we only increment lemma2's value in the hashmap if:
					-it's a noun
					-it is not "player" or some synonym (0 is the index of the Wordnet 3.0 definition of "player" related to gameplay) 
					-it's not one of the moveTypes (phrases like "make a jump" are common enough that they usually get counted instead of
					piece types if this isn't checked) */
					if (pos2.charAt(0) == 'N' && !RulesParser.isSynonymOf("player", lemma2, 0) && !moveTypes.contains(lemma2))
						arguments.put(lemma2, arguments.get(lemma2)+1); //increment value in hashmap
				}
				/* if the previous if statement was false, the following if statement checks if lemma2 is:
				- any of the move types in moveTypes
				- any synonym of the verb "reach"
				- any synonym of the verb "become"
				 */
				else if (moveTypes.contains(lemma2) || RulesParser.isSynonymOf("reach", lemma2) || RulesParser.isSynonymOf("become", lemma2))
				{	//if so, we inspect lemma1
					/* we only increment lemma1's value in the hashmap if:
					-it's a noun
					-it is not "player" or some synonym (0 is the index of the Wordnet 3.0 definition of "player" related to gameplay) 
					-it's not one of the moveTypes (phrases like "make a jump" are common enough that they usually get counted instead of
					piece types if this isn't checked) */
					if (pos1.charAt(0) == 'N' && !RulesParser.isSynonymOf("player", lemma1, 0) && !moveTypes.contains(lemma1)) 
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
				int index1 = RulesParser.isolateIndexFromDependency(d,1);
				int index2 = RulesParser.isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos1 = partsOfSpeech[i][index1-1];
				String pos2 = partsOfSpeech[i][index2-1];

				// The following checks for noun appositive phrases modifying name, or being modified by name.
				if (d.contains("appos("))
				{
					// The following checks if name is being modified by a noun appositive phrase.
					if (lemma1.equals(name) && pos2.charAt(0) == 'N')
					{ 	//If so, lemma2 is the modifier, and likely denotes an equivalent type to name.
						/* Neither a move type nor any word referring to a player is ever the name of a piece; also,
						we don't want to simply add the same name as its own equivalent. */
						if (!moveTypes.contains(lemma2) && !RulesParser.isSynonymOf("player", lemma2, 0) && !lemma2.equals(name))
						{
							currentPiece.addEquivalentType(lemma2);
							System.out.println("Equivalent type parsed for " + name + " in sentence " + i + ": " + lemma2);
						}
					}
					// If not, the following checks if name is the head word in a appositive phrase modifying another noun.
					else if (lemma2.equals(name) && pos1.charAt(0) == 'N')
					{ 	// If so, lemma1 is the modified noun, and likely denotes an equivalent type to name.
						/* Neither a move type nor any word referring to a player is ever the name of a piece; also,
						we don't want to simply add the same name as its own equivalent. */
						if (!moveTypes.contains(lemma1) && !RulesParser.isSynonymOf("player", lemma1, 0) && !lemma1.equals(name))
						{
							currentPiece.addEquivalentType(lemma1);
							System.out.println("Equivalent type parsed for " + name + " in sentence " + i + ": " + lemma1);
						}

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
					else if (!moveTypes.contains(lemma1) && !RulesParser.isSynonymOf("player", lemma1, 0))
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
					else if (aclModifiesName && !moveTypes.contains(lemma1) && 
					 !RulesParser.isSynonymOf("player", lemma1, 0) && !lemma2.equals(name))
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
			boolean isRenamingPredicate = false; //used for constructions with "called" or "known as" as predicates
			boolean isModifiedByNow = false; 

			String transitionPieceName = null;
			String antecedent = null;

			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = RulesParser.isolateIndexFromDependency(d,1);
				int index2 = RulesParser.isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos1 = partsOfSpeech[i][index1-1];
				String pos2 = partsOfSpeech[i][index2-1];

				/*The following checks for subject dependencies in the current sentence. */
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
						antecedent = parent.determineAntecedent(i, index2);
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
				/* The following checks for sentences with the predicates "know" or "call", the former taking
				a prepositional phrase headed by "as" as an argument and the latter taking a noun direct object. */
				if ((d.contains("nmod:as") && lemma1.equals("know")) || (d.contains("dobj") && lemma1.equals("call")))
				{
					isRenamingPredicate = true;
					transitionPieceName = lemma2;
				}
				/* We only consider predicate nominatives or either of the "renaming predicates" as transition sentences 
				if they are modified by the adverb "now", so the following checks for that. */
				if (d.contains("advmod(") && lemma2.equals("now"))
				{
					//check for a predicate nominative
					if (pos1.charAt(0) == 'N' && lemma1.equals(transitionPieceName))
						isModifiedByNow = true;
					//check for a "renaming predicate"
					else if (lemma1.equals("know") || lemma1.equals("call"))
						isModifiedByNow = true;
				}
			}
			//Sentences with either predicate nominatives or either of the renaming predicates, modified by "now", are transition sentences	
			if ((isPredicateNominative || isRenamingPredicate) && isModifiedByNow)
				isTransitionSentence = true;

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
				// we also have to use this transition sentence to try to parse the transition zone for this new piece type
				parseTransitionZones(transitionPiece, i); 
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
				int index1 = RulesParser.isolateIndexFromDependency(d,1);
				int index2 = RulesParser.isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1-1];
				String lemma2 = lemmas[i][index2-1];
				String pos2 = partsOfSpeech[i][index2-1];

				/* The following checks if the sentence contains either of the predicates "become" or "make", specifically taking any 
				of the names of currentPiece as either its direct object or its open clausal complement. */
				if ((d.contains("dobj") || d.contains("xcomp")) && 
					(lemma1.equals("become") || lemma1.equals("make")) && 
					currentPiece.isAnyName(lemma2))
					isObjectName = true;
				/* The following checks if the sentence contains the predicate "turn", specifically taking a prepositional
				phrase headed by either "to" or "into" which takes any of the names of currentPiece as its object.*/
				else if ((d.contains("nmod:into") || d.contains("nmod:to")) && lemma1.equals("turn") && currentPiece.isAnyName(lemma2))
					isObjectName = true;

				/* The following checks if the sentence contains either of the predicates "become" or "turn", specifically 
				taking either a noun or a pronoun argument as its subject. 
				If the subject is a noun, it is assumed to be a piece name and stored in previousPieceName.
				If the subject is a pronoun, we call parent.determineAntecedent() to determine what noun the pronoun refers to. 
				If this fails (as it often does, because CoreNLP), we search for the predicate "reach" or any synonym of it 
				in the sentence, and see what its subject is. This solution is not perfect, but a sufficient backup. */
				if ((d.contains("nsubj")) && (lemma1.equals("become") || lemma1.equals("turn")))
				{
					if (pos2.charAt(0) == 'N')
						previousPieceName = lemma2;
					else if (pos2.equals("PRP"))
					{
						String antecedent = parent.determineAntecedent(i,index2);
						if (!antecedent.equals(""))
							previousPieceName = antecedent;
						else if (subjectOfReach != null)
							previousPieceName = subjectOfReach;
					}
				}
				/* The following checks if the sentence contains the predicate "make", necessarily in the passive voice, specifically 
				taking either a noun or a pronoun argument as its subject. 
				If the subject is a noun, it is assumed to be a piece name and stored in previousPieceName.
				If the subject is a pronoun, we call parent.determineAntecedent() to determine what noun the pronoun refers to. 
				If this fails (as it often does, because CoreNLP), we search for the predicate "reach" or any synonym of it 
				in the sentence, and see what its subject is. This solution is not perfect, but a sufficient backup. */
				else if (d.contains("nsubjpass") && lemma1.equals("make"))
				{
					if (pos2.charAt(0) == 'N')
						previousPieceName = lemma2;
					else if (pos2.equals("PRP"))
					{
						String antecedent = parent.determineAntecedent(i,index2);
						if (!antecedent.equals(""))
							previousPieceName = antecedent;
						else if (subjectOfReach != null)
							previousPieceName = subjectOfReach;
					}
				}
				/* The following checks if the sentence contains the predicate "reach"; if so, if its subject is a noun,
				it is stored in subjectOfReach. previousPieceName is set to this when the subject of become is
				a pronoun and no other antecedent can be determined using parent.determineAntecedent() */
				if (d.contains("nsubj") && RulesParser.isSynonymOf("reach", lemma1))
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
				/*since we've determined that the ith sentence is a transition sentence describing the transition to currentPiece, 
				we have to try to parse transition zones for currentPiece with it */
				parseTransitionZones(currentPiece, i); 

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

	public void parseTransitionZones(Piece transitionPiece, int sentenceInd)
	{
		//semantic dependency graph of sentence with index sentenceInd
		SemanticGraph graph = sentences.get(sentenceInd).get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
		ArrayList<Integer> indicesOfReach = new ArrayList<Integer>(1); //indices in sentenceInd of synonyms of reach or hyponyms of move

		//iterate over all lemmas in current sentence (lemmas[sentenceInd]), finding any synonyms of reach or hyponyms of move
		for (int i = 0; i < lemmas[sentenceInd].length; i++)
		{
			String lemma = lemmas[sentenceInd][i];
			if (RulesParser.isSynonymOf("reach", lemma) || RulesParser.isHypernymOf("move", lemma))
				indicesOfReach.add(i);
		}

		/* iterate over any indices of any synonym of reach/hyponyms of move (there is probably only 1, but just in case), finding 
		those that head adverbial or adjectival subordinate clauses; all such indices will be stored in reachSubordClause */
		ArrayList<Integer> reachSubordClause = new ArrayList<Integer>(1); 
		for (int i: indicesOfReach)
		{
			IndexedWord reachNode = graph.getNodeByIndexSafe(i+1); 
			//synonym of reach in the SemanticGraph (have to add 1 because SemanticGraph nodes are indexed from 1, not 0)
			IndexedWord parentNode = graph.getParent(reachNode); //parent of reachNode

			String relnName = null;
			if (reachNode != null && parentNode != null)
				relnName = graph.reln(parentNode, reachNode).getShortName();
			/* relnName is the grammatical relation between the synonym of reach and its parent node in the semantic 
			dependency graph (we want this to either be an adverbial or adjectival clause) */
			if (relnName != null && (relnName.equals("advcl") || relnName.equals("acl:relcl")))
				reachSubordClause.add(i);
		}

		/* we want to find adjectives denoting nearness or farness modifiyng "row" that are within the subordinate clause
		in the sentence; that is, those that are dominated by any of the synonyms of reach indexed in reachSubordClause */
		
		//dependencies for sentenceInd as a String[], each entry containing a single dependency String
		String[] dependencies = graph.toString(SemanticGraph.OutputFormat.LIST).split("\n");

		boolean isTransitionZone = false;
		boolean isFurthestRow = false;
		boolean isClosestRow = false;

		boolean otherPlayer = false; //used for constructions like "if you reach the other player's side"

		//iterate over all dependencies
		for (int i = 1; i < dependencies.length; i++)
		{
			String d = dependencies[i];
			int index1 = RulesParser.isolateIndexFromDependency(d,1);
			int index2 = RulesParser.isolateIndexFromDependency(d,2);
			String lemma1 = lemmas[sentenceInd][index1-1];
			String lemma2 = lemmas[sentenceInd][index2-1];
			String pos2 = partsOfSpeech[sentenceInd][index2-1];

			// The following checks for adjectives modifying nouns
			if (d.contains("amod(") && pos2.contains("JJ"))
			{
				//The following checks if the noun being modified is any of "row", "rank", "side", "edge", or "end"
				if (lemma1.equals("row") || lemma1.equals("rank") || lemma1.equals("side") || lemma1.equals("edge") || lemma1.equals("end"))
				{
					/*The following checks if the modifying adjective is dominated by the subordinate clause predicates indexed by
					reachSubordClause; that is, this checks if this modifying adjective is within the subordinate clause. */
					if (parent.dominates(sentenceInd, reachSubordClause, index2))
					{
						/* The following checks if the modifying adjective is any synonym of any of the following: "furthest",
						"opposite", "far", or "last". These all correspond to the furthest row being the transition zone. */
						if (RulesParser.isSynonymOf("furthest", lemma2) || RulesParser.isSynonymOf("opposite", lemma2) || 
							RulesParser.isSynonymOf("far", lemma2) || RulesParser.isSynonymOf("last", lemma2))
						{
							isTransitionZone = true;
							isFurthestRow = true;
						}
						/* The following checks if the modifying adjective is any synonym of any of the following: "own",
						"nearest", or "first". These all correspond to the closest row being the transition zone. */
						if (RulesParser.isSynonymOf("own", lemma2) || RulesParser.isSynonymOf("nearest", lemma2) || 
							RulesParser.isSynonymOf("first", lemma2))
						{
							isTransitionZone = true;
							isClosestRow = true;
						}
					}
				}
				/* The following checks if the modified noun is any synonym of "player", and if the modifying adjective is any 
				synonym of either "other" or "opposing". This is necessarily for constructions like "the other player's side". */
				else if (RulesParser.isSynonymOf("player", lemma1) && 
					(RulesParser.isSynonymOf("other", lemma2) || RulesParser.isSynonymOf("opposing", lemma2)))
					otherPlayer = true;
			}
			//The following checks for nouns modified by possessive forms
			else if (d.contains("nmod:poss("))
			{
				//The following checks if the possessed noun is any of "row", "rank", "side", "edge", or "end"
				if (lemma1.equals("row") || lemma1.equals("rank") || lemma1.equals("side") || lemma1.equals("edge") || lemma1.equals("end"))
				{
					/*The following checks if the possessing noun is dominated by the subordinate clause predicates indexed by
					reachSubordClause; that is, this checks if this possessing noun is within the subordinate clause. */
					if (parent.dominates(sentenceInd, reachSubordClause, index2))
					{
						/* The following checks if the possessing noun is any synonym of "opponent". 
						This corresponds to the furthest row being the transition zone. */
						if (RulesParser.isSynonymOf("opponent", lemma2))
						{
							isTransitionZone = true;
							isFurthestRow = true;
						}
						/* The following checks if the possessing noun is any synonym of "player", and if it is modified by
						any adjective synonym of "other" (that is, if otherPlayer is true - we checked for this earlier.)
						This corresponds to the furthest row being the transition zone. */
						else if (otherPlayer && RulesParser.isSynonymOf("player", lemma2))
						{
							isTransitionZone = true;
							isFurthestRow = true;
						}
					}
				}
			}
		}

		if (isTransitionZone)
		{
			System.out.print("Transition zone for " + transitionPiece.getName() + " parsed:");
			if (isFurthestRow)
			{
				System.out.print(" furthest row");
				transitionPiece.setIsFurthestRow(true);
			}
			if (isClosestRow)
			{
				System.out.print(" closest row");
				transitionPiece.setIsClosestRow(true);
			}
			System.out.println();

			editTransitionZones(transitionPiece, isFurthestRow, isClosestRow);
		}
	}

	public void editTransitionZones(Piece transitionPiece, boolean isFurthestRow, boolean isClosestRow)
	{
		/* This entire method is dependent on assuming there are only two players in the game, and that P1 starts 
		from the top of the board and P2 starts from the bottom of the board (as assumed by ZRFWriter). */
		String name = transitionPiece.getName();
		if (isFurthestRow)
		{  
			//since P1 starts from the top, the furthest row is the bottom row (transitionZones[transitionZones.length-1])
			for (int i = 0; i < transitionZones[transitionZones.length-1].length; i++)
			{
				String s = transitionZones[transitionZones.length-1][i];
				if (s == null)
					transitionZones[transitionZones.length-1][i] = "P1-" + name;
				else
					transitionZones[transitionZones.length-1][i] = s + "/" + "P1-" + name;
			}
			//since P2 starts from the bottom, the furthest row is the top row (transitionZones[0])
			for (int i = 0; i < transitionZones[0].length; i++)
			{
				String s = transitionZones[0][i];
				if (s == null)
					transitionZones[0][i] = "P2-" + name;
				else
					transitionZones[0][i] = s + "/" + "P2-" + name;
			}
		}
		if (isClosestRow)
		{
			//since P1 starts from the top, the closest row is the top row (transitionZones[0])
			for (int i = 0; i < transitionZones[0].length; i++)
			{
				String s = transitionZones[0][i];
				if (s == null)
					transitionZones[0][i] = "P1-" + name;
				else
					transitionZones[0][i] = s + "/" + "P1-" + name;
			}
			//since P2 starts from the bottom, the closest row is the bottom row (transitionZones[transitionZones.length-1])
			for (int i = 0; i < transitionZones[transitionZones.length-1].length; i++)
			{
				String s = transitionZones[transitionZones.length-1][i];
				if (s == null)
					transitionZones[transitionZones.length-1][i] = "P2-" + name;
				else
					transitionZones[transitionZones.length-1][i] = s + "/" + "P2-" + name;
			}

		}

	}
}