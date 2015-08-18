import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.util.*;

public class EndParser
{

	private RulesParser parent;
	private List<CoreMap> sentences;
	private String[][] lemmas; //lemmas[i][j] holds the lemma of the jth word in the ith sentence of the text
	private String[][] partsOfSpeech; //partsOfSpeech[i][j] holds the POS of the jth word in the ith sentence of the text

	private ArrayList<Piece> pieceTypes;

	private ArrayList<EndCondition> endConditions;

	public EndParser(RulesParser parent, List<CoreMap> sentences, String[][] lemmas, String[][] partsOfSpeech, ArrayList<Piece> pieceTypes)
	{
		this.parent = parent;
		this.sentences = sentences;
		this.lemmas = lemmas;
		this.partsOfSpeech = partsOfSpeech;
		this.pieceTypes = pieceTypes;

		this.endConditions = new ArrayList<EndCondition>(1);
	}

	public ArrayList<EndCondition> getEndConditions()
	{
		return this.endConditions;
	}

	public HashMap<Integer,String> determineEndConditionSentences()
	{
		/* The following method determines candidate end condition sentences by searching all lemmas in all sentences for the following words:
		- "win", any noun synonym of "objective", any noun synonym of "goal" (sentences with these potentially describe a win condition)
		- "lose", any verb synonym of "end" (sentences with these potentially describe a lose condition)
		- any noun synonym of "tie", or the noun "stalemate" (sentences with these potentially describe a draw condition)
		It is assumed that no single sentence describes multiple types of conditions (eg, both a win condition and a lose condition.)
		However, a single sentence may describe multiple end conditions of the same time (eg, two different lose conditions.) 
		The indices of each of these sentences is stored as the keys in the hashamp endConditionSentences, with the type of condition
		the sentence is determined to potentially describe as the value corresponding to each key. */

		HashMap<Integer,String> endConditionSentences = new HashMap<Integer,String>();
		for (int i = 0; i < sentences.size(); i++)
		{
			for (int j = 0; j < lemmas[i].length; j++)
			{
				String lemma = lemmas[i][j];
				String pos = partsOfSpeech[i][j];

				if (!endConditionSentences.containsKey(i))
				{
					if (lemma.equals("win"))
						endConditionSentences.put(i, EndCondition.WIN);
					else if (pos.charAt(0) == 'N' && (RulesParser.isSynonymOf("objective", lemma) || RulesParser.isSynonymOf("goal",lemma)))
						endConditionSentences.put(i, EndCondition.WIN);
					else if (lemma.equals("lose"))
						endConditionSentences.put(i, EndCondition.LOSE);
					else if (pos.charAt(0) == 'N' && RulesParser.isSynonymOf("tie", lemma, 5)) //5 is wordnet index of "tie" relating to games
						endConditionSentences.put(i, EndCondition.DRAW);
					else if (pos.charAt(0) == 'N' && lemma.equals("stalemate"))
						endConditionSentences.put(i, EndCondition.DRAW);
					else if (pos.charAt(0) == 'V' && RulesParser.isSynonymOf("end", lemma))
						endConditionSentences.put(i, EndCondition.LOSE); 
				}
			}
		}

		/* Also, constructions like the following are pretty common: 
		"A player wins the game when the opponent cannot make a move. In most cases, this is because all of the 
		opponent's pieces have been captured, but it could also be because all of his pieces are blocked in." 
		In this pair of sentences, the first one gets has already been parsed and put in endConditionSentences, as it contains 
		the word "win"; however, the second sentence also importantly describe the nature of win conditions, referencing the 
		previous sentence with the pro-sentence "this". 
		Thus, we must iterate over each sentence (sentenceIndex) placed as a key in endConditionSentences, and check the following:
		 - if sentence w/ index (sentenceIndex-1) is not already a key in sentenceIndex, and the sentence w/ index (sentenceIndex) has 
		a pro-sentence, then the pro-sentence is likely referring to sentenceIndex-1. (We check by seeing if a determiner occupies the 
		place of a noun in the list of dependencies). If so, we put sentenceIndex-1 in the hashmap, using the same value as sentenceIndex.
		  - eg: "Continue jumping your opponent's pieces until they are all removed. Once you have done this, you've won!"
		- if sentence w/ index (sentenceIndex+1) is not already a key in sentenceIndex, and the sentence w/ index (sentenceIndex+1) has 
		a pro-sentence, then the pro-sentence is likely referring to sentenceIndex. If so, we put sentenceIndex+1 in the hashmap, 
		using the same value as sentenceIndex. */

		/* instead of iterating directly over endConditionSentences.keySet(), we make an array containing all the keys
		and iterate over that, in order to avoid ConcurrentModificationExceptions */
		int[] sentenceIndices = new int[endConditionSentences.size()];
		int arrayInd = 0;
		for (int key: endConditionSentences.keySet())
		{
			sentenceIndices[arrayInd] = key;
			arrayInd++;
		}

		for (int sentenceIndex: sentenceIndices)
		{
			if (!endConditionSentences.containsKey(sentenceIndex-1))
			{
				//dependencies for sentenceIndexth sentence as a String[], each entry containing a single dependency String
				String[] dependencies = sentences.get(sentenceIndex).get(
					SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
					SemanticGraph.OutputFormat.LIST).split("\n");
				for (int j = 1; j < dependencies.length; j++)
				{
					String d = dependencies[j];
					int index2 = RulesParser.isolateIndexFromDependency(d,2);
					String pos2 = partsOfSpeech[sentenceIndex][index2];

					if (pos2.equals("DT") &&
						(d.contains("dobj(") || d.contains("nsubj(") || d.contains("xcomp(") || d.contains("nmod:")))
					{
						endConditionSentences.put(sentenceIndex-1, endConditionSentences.get(sentenceIndex));
						break;
					}
				}
			}
			if (!endConditionSentences.containsKey(sentenceIndex+1) && sentenceIndex+1 < sentences.size())
			{
				//dependencies for sentenceIndex+1th sentence as a String[], each entry containing a single dependency String
				String[] dependencies = sentences.get(sentenceIndex+1).get(
					SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
					SemanticGraph.OutputFormat.LIST).split("\n");
				for (int j = 1; j < dependencies.length; j++)
				{
					String d = dependencies[j];
					int index2 = RulesParser.isolateIndexFromDependency(d,2);
					String pos2 = partsOfSpeech[sentenceIndex+1][index2];

					if (pos2.equals("DT") &&
						(d.contains("dobj(") || d.contains("nsubj(") || d.contains("xcomp(") || d.contains("nmod:")))
					{
						endConditionSentences.put(sentenceIndex+1, endConditionSentences.get(sentenceIndex));
						break;
					}
				}
			}
		}
		return endConditionSentences;

	}

	public void parseEndConditions()
	{
		/* The following method parses end conditions. First, we call determineEndConditionSentences() to determine
		what sentences in the ruleset potentially describe end conditions.*/
		HashMap<Integer, String> endConditionSentences = determineEndConditionSentences();

		/* Now, we iterate over all the sentences found, and search for constructions that describe end conditions.
		This system supports two kinds of end conditions, which are directly supported by the ZRF format: a player being stalemated 
		(ie, unable to make a move), and a player having a certain number of pieces remaining (in the case of checkers, 0 being a loss). 

		For a stalemated condition, the system searches for the following constructions:
		1. the predicate "move" being negated (eg: "when a player can not move")
		2. a modifier or argument of the predicate "move" being negated (eg: "when a player can no longer move")
		3. the predicate "move" being dominated by any verb synonym of the verb "prevent" (eg: "prevent your opponent from moving")
		4. the noun "move" being negated (eg: "when a player can make no move")
		5. the predicate taking the noun "move" as an argument being negated (eg: "when a player can't make a move")
		6. a modifier of the noun "move" being negated (eg: "when a player has no available moves")
		7. any verb synonym of "block" taking any phrase denoting "all of the pieces" (where "pieces" can be substitued with
		any of the parsed piece names) as an argument (eg: "when all of your pieces are blocked")

		A pieces-remaining condition notably requires a quantifier value (that is, the number of pieces that must remain for the condition
		to be true.) This value is stored in the integer variable "quantifier".
		For a pieces-remaining condition, the system searches for the following constructions:
		8. any of the verbs "capture", "remove" or "lose" taking any phrase denoting "all of the pieces" (where "pieces" can be 
		substitued with any of the parsed piece names) as an argument (eg: "when all of your pieces are blocked") 
		   - this necessarily implies the quantifier = 0
		9. the verb "have" taking the noun phrase "pieces left" or "pieces remaining" as an argument, where "pieces" can be 
		substitued with any of the parsed piece names, and where this argument is either
		   - negated (quantifier = 0)
		   - modified by a number N (quantifier = N)
		10. the verb "have" taking the noun phrase "no more pieces" as an argument (quantifier = 0)

		Often, rulesets will define an end condition for the player by describing a state of the player's opponent. For example:
		"A player wins the game when the opponent cannot make a move." defines a win condition for the player by describing
		the stalemated condition of their opponent (the opponent's lose condition). 
		To acommodate for this, we have to analyze the constructions we search for to see if they describe the opponent or 
		the player, and if they describe the opponent, consider this end condition to be of the opposite type. (The boolean
		variable isOppositeType handles this.)
		In the previously described constructions numbered 1, 2, 3*, 4, 5, 6, 9, 10, we searched for predicates: either "move" (v),
		a verb taking "move" (n) as an argument, or "have" taking "piece" as an argument. We test for isOppositeType by seeing if 
		the predicate takes any noun phrase denoting "opponent" as an argument. (Such NPs include: "other player", "opposing player",
		and any noun synonym of "opponent".)
		 - (eg: "When the opponent can not make a move" "When the other player has no pieces left")
		 - *Also, in case 3, isOppositeType is set true if any NP denoting "opponent" is an argument of the predicate "prevent".
		In the previously desribed constructions numbered 7, 8, we searched for DPs denoting "all of the pieces". We test for 
		isOppositeType by seeing if the noun in this DP is possessed by any NP denoting "opponent". 
		 - (eg: "When all of the other player's pieces are captured" "When you have blocked all the opponent's pieces") */
		for (int i: endConditionSentences.keySet())
		{
			//current sentence
			CoreMap sentence = sentences.get(i);
			//semantic dependency graph of current sentence
			SemanticGraph graph = sentence.get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
			//dependencies for current sentence as a String[], each entry containing a single dependency String
			String[] dependencies = graph.toString(SemanticGraph.OutputFormat.LIST).split("\n");

			boolean isWin = false; // whether or not this sentence describes a win condition
			boolean isLose = false; // whether or not this sentence describes a lose condition
			boolean isDraw = false; // whether or not this sentence describes a draw condition

			boolean isOppositeType = false;

			boolean isStalemated = false; // whether or not this sentence describes a stalemated end condition
			boolean isPiecesRemaining = false; // whether or not this sentence describes a pieces-remaining end condition

			int preventInd = -1; // index of a verb synonym of "prevent"

			boolean isCaptureAll = false; //representing the dobj(capture, all) dependency in a statement like "capture all of the pieces"
			boolean isBlockAll = false; //representing the dobj(block, all) dependency in a statement like "block all of the pieces"
			boolean isAllPieces = false; //representing the nmod:of(all, pieces) dependency in either of the previous two statements

			boolean isPieceLeft = false; //representing the acl(piece, left) dependency in a statement like "has no pieces left"
			boolean isHaveMore = false; //representing the advmod(has, more) dependency in a statement like "has no more pieces"
			boolean isHavePiece = false; //representing the dobj(has, pieces) dependency in either of the previous two statements

			int quantifier = -1;

			ArrayList<Integer> negatedWords = new ArrayList<Integer>(1); //list of all words directly governing a negation word
			List<SemanticGraphEdge> negativeEdges = graph.findAllRelns(UniversalEnglishGrammaticalRelations.NEGATION_MODIFIER);
			for (SemanticGraphEdge edge: negativeEdges)
				negatedWords.add(edge.getGovernor().index()-1);

			//iterate over all dependencies
			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = RulesParser.isolateIndexFromDependency(d,1);
				int index2 = RulesParser.isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1];
				String lemma2 = lemmas[i][index2];
				String pos1 = partsOfSpeech[i][index1];
				String pos2 = partsOfSpeech[i][index2];

				//check for "move" as a verb being negated, or a modifier/argument of it being negated
				if (lemma1.equals("move") && pos1.charAt(0) == 'V')
				{
					// check for the verb "move" being negated
					if (negatedWords.contains(index1))
						isStalemated = true;
					//check for a modifier or argument of the verb being negated
					else if (negatedWords.contains(index2))
						isStalemated = true;
					//check for the verb "move" being dominated by the verb "prevent"
					else if (preventInd != -1 && parent.dominates(i, preventInd, index1))
					{
						isStalemated = true;
						if (isOpponentArgument(i, preventInd))
							isOppositeType = true;
					}

					/* If either of the negation checks worked, then isStalemated is true; if so, we now have to check any arguments 
					of the verb "move" and see if they are any phrase denoting the opponent. We do this by calling
					isOpponentArgument(). (If these are both true, isOppositeType must be set true.) */
					if (isStalemated && isOpponentArgument(i, index1))
						isOppositeType = true;
						
				}
				// check for "move" as a noun being negated, or the verb taking it as an argument 
				else if (lemma2.equals("move") && pos2.charAt(0) == 'N') 
				{
					//check for the noun "move" being negated
					if (negatedWords.contains(index2))
						isStalemated = true;
					// check for the verb taking "move" as an argument being negated
					else if (negatedWords.contains(index1))
						isStalemated = true;

					/* If either of these checks worked, then isStalemated is true; if so, we now have to check any 
					arguments of the verb taking the noun "move" as an argument and see if they are any phrase denoting the opponent. 
					We do this by calling isOpponentArgument(). (If these are both true, isOppositeType must be set true.) */
					if (isStalemated && isOpponentArgument(i, index1))
						isOppositeType = true;
				}
				// check for dependencies in which "move" is a noun modified by an adjective, and check if the modifier is negated
				else if (lemma1.equals("move") && pos1.charAt(0) == 'N')
				{
					//check for the modifier of the noun "move" being negated
					if (negatedWords.contains(index2))
						isStalemated = true;
					//we don't have to check if the noun "move" is negated, that's already checked in the previous if block
				}
				//check for synonyms of the word "prevent"
				else if (RulesParser.isSynonymOf("prevent", lemma1))
					preventInd = index1;
				// check for a verb taking a DP headed by "all" as an argument
				else if (pos1.charAt(0) == 'V' && pos2.equals("DT") && lemma2.equals("all"))
				{
					/* if the verb is "capture", "remove", or "lose", set isCaptureAll true (if isAllPieces is also true,
					isPiecesRemaning will be set true and quantifier will be set to 0) */
					if (lemma1.equals("capture") || lemma1.equals("remove") || lemma1.equals("lose"))
						isCaptureAll = true;
					/* if the verb is any synonym of "block", set isBlockAll true (if isAllPieces is also true,
					isStalemated will be set true */
					else if (RulesParser.isSynonymOf("block", lemma1))
						isBlockAll = true;
				}
				//check for a DP headed by "all" and taking a piece name as its complement
				else if (pos1.equals("DT") && lemma1.equals("all") && isPieceName(lemma2))
				{
					//set is all pieces true; the check for "dep(" is if CoreNLP fails to figure out the phrase structure
					if (d.contains("nmod:of") || d.contains("dep("))
						isAllPieces = true;

					//if the piece name is possessed by any NP denoting "opponent", set isOppositeType true
					if (isOpponentPossessor(i, index2))
						isOppositeType = true;
				}
				/* check for a DP headed by "all" and taking a piece name as its complement (this is only if CoreNLP really fails to 
				figure out the phrase structure) */
				else if (d.contains("dep") && pos2.equals("DT") && lemma2.equals("all") && isPieceName(lemma1))
				{
					isAllPieces = true;

					//if the piece name is possessed by any NP denoting "opponent", set isOppositeType true
					if (isOpponentPossessor(i, index1))
						isOppositeType = true;
				}
				//check for a predicate taking a piece name as an argument
				else if (pos1.charAt(0) == 'V' && isPieceName(lemma2))
				{
					//check if the verb is "have"
					if (lemma1.equals("have"))
					{
						isHavePiece = true; //if isPieceLeft or isHaveMore is true, isPiecesRemaining will be set to true

						//check if any arguments of the verb "have" are any phrase denoting the opponent
						if (isOpponentArgument(i, index1))
							isOppositeType = true;
					}
					/* CoreNLP sometimes botches phrases like "capture all the pieces" and will consider 
					"pieces" to be the head of the DP, thus resulting in dependencies like dobj(capture, pieces)
					when it should parse dobj(capture, all). Thus, if we find something like (capture, pieces), we have
					to treat it the same as dobj(capture, all) (ie, setting isCaptureAll to true). */
					else if (lemma1.equals("capture") || lemma1.equals("remove") || lemma1.equals("lose"))
						isCaptureAll = true;
					// The same goes for when the verb is "block".
					else if (RulesParser.isSynonymOf("block", lemma1))
						isBlockAll = true;
				}
				// check for a noun piece name modified by a adjectival clause
				else if ((d.contains("acl(") || d.contains("acl:")) && isPieceName(lemma1))
				{
					//if the adjectival clause is headed by "leave" or "remain"
					if (pos2.charAt(0) == 'V' && (lemma2.equals("leave") || lemma2.equals("remain")))
					{
						isPieceLeft = true; 
						if (negatedWords.contains(index1)) //if the piece name is negated, (eg "no pieces left")
							quantifier = 0; //this denotes zero pieces remaining
						else //see if any numbers modify the piecename
						{
							Set<IndexedWord> numericModifiers = graph.getChildrenWithReln(
								graph.getNodeByIndexSafe(index1+1), UniversalEnglishGrammaticalRelations.NUMERIC_MODIFIER);
							for (IndexedWord numberWord: numericModifiers) 
							{	/* there should logically only be at most 1 numeric modifier in this context (it doesn't make sense
							 	to say "you lose when you have only one or two pieces left," as it is sufficient to just say "two") 
							 	- but in the case of multiple, this arbitrarily sets quantifier to be the last one in the set.
							 	this could be altered to make quantifier a list and to produce separate end conditions for each 
							 	list entry, if necessary (it's unnecessary for checkers) */
								try
								{
									String numberString = sentence.get(CoreAnnotations.TokensAnnotation.class).get(
										numberWord.index()-1).get(CoreAnnotations.NormalizedNamedEntityTagAnnotation.class);
									if (numberString != null)
									{
										int number = (int) Double.parseDouble(numberString);
										quantifier = number;
									}
								}
								catch (NumberFormatException e)
								{ /* ignore; if the NER doesn't parse a number, we just ignore it and don't change quantifier*/ }
							}
						}
					}
				}
				//check for a construction like "have no more pieces"
				else if (d.contains("advmod(") && lemma1.equals("have") && lemma2.equals("more"))
				{
					isHaveMore = true;
					if (negatedWords.contains(index2))
						quantifier = 0;
				}
			}

			//handle statements like "capture all of the pieces" / "block all of the pieces"
			if (isAllPieces)
			{
				if (isCaptureAll)
				{
					quantifier = 0;
					isPiecesRemaining = true;
				}
				else if (isBlockAll)
					isStalemated = true;
			}
			// handle statements like "has no pieces left" / "has no more pieces"
			if (isHavePiece && (isPieceLeft || isHaveMore))
				isPiecesRemaining = true;

			//determine type of end condition (win, lose, draw)
			if (endConditionSentences.get(i).equals(EndCondition.WIN))
			{
				if (isOppositeType)
					isLose = true;
				else
					isWin = true;
			}
			else if (endConditionSentences.get(i).equals(EndCondition.LOSE))
			{
				if (isOppositeType)
					isWin = true;
				else
					isLose = true;
			}
			else
				isDraw = true;

			//TODO: REMOVE! ALL debugging
			/*System.out.println("Sentence " + i + " isWin: " + isWin);
			System.out.println("Sentence " + i + " isLose: " + isLose);
			System.out.println("Sentence " + i + " isOppositeType: " + isOppositeType);
			System.out.println("Sentence " + i + " isStalemated: " + isStalemated);
			System.out.println("Sentence " + i + " isPiecesRemaining: " + isPiecesRemaining);*/

			if (isWin)
			{
				if (isStalemated)
				{
					endConditions.add(new EndCondition(EndCondition.WIN, EndCondition.STALEMATED));
					System.out.println("Win condition parsed: stalemated in sentence " + i);
				}
				if (isPiecesRemaining && quantifier > -1)
				{
					endConditions.add(new EndCondition(EndCondition.WIN, EndCondition.PIECES_REMAINING, quantifier));
					System.out.println("Win condition parsed: pieces remaining = " + quantifier + " in sentence " + i);
				}
			}
			if (isLose)
			{
				if (isStalemated)
				{
					endConditions.add(new EndCondition(EndCondition.LOSE, EndCondition.STALEMATED));
					System.out.println("Lose condition parsed: stalemated in sentence " + i);
				}
				if (isPiecesRemaining && quantifier > -1)
				{
					endConditions.add(new EndCondition(EndCondition.LOSE, EndCondition.PIECES_REMAINING, quantifier));
					System.out.println("Lose condition parsed: pieces remaining = " + quantifier + " in sentence " + i);
				}
			}
			if (isDraw)
			{
				if (isStalemated)
				{
					endConditions.add(new EndCondition(EndCondition.DRAW, EndCondition.STALEMATED));
					System.out.println("Draw condition parsed: stalemated in sentence " + i);
				}
				if (isPiecesRemaining && quantifier > -1)
				{
					endConditions.add(new EndCondition(EndCondition.DRAW, EndCondition.PIECES_REMAINING, quantifier));
					System.out.println("Draw condition parsed: pieces remaining = " + quantifier + "in sentence " + i);
				}
			}
			
		}
	}

	/**
	Helper method to parseEndConditions() - given a predicate (indexed by predicateIndex) in a sentence
	(indexed by sentenceIndex), determines if the predicate takes any phrase denoting "opponent" as an argument.
	*/
	public boolean isOpponentArgument(int sentenceIndex, int predicateIndex)
	{
		//semantic dependency graph of the sentence
		SemanticGraph graph = sentences.get(sentenceIndex).get(
			SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
		//check for any of the arguments of the verb being any phrase denoting the other player
		IndexedWord predicateNode = graph.getNodeByIndexSafe(predicateIndex+1); //+1 because SemanticGraph nodes are indexed from 1
		Set<IndexedWord> children = null;
		if (predicateNode != null) 
			children = graph.getChildren(predicateNode);
		for (IndexedWord child: children)
		{
			String childLemma = lemmas[sentenceIndex][child.index()-1]; //the lemma of the word each child node represents
			String childPOS = partsOfSpeech[sentenceIndex][child.index()-1]; // POS of the word each child node represents
			//in both of the previous Strings, we subtract 1 from child.index() b/c SemanticGraph nodes are indexed from 1
			if (RulesParser.isSynonymOf("opponent", childLemma))
				return true;
			/* if the child isn't a synonym of opponent, we check if it's a noun, and if so, 
			we check if it is modified by "other" or "opposing */
			else if (childPOS.charAt(0) == 'N')
			{
				Set<IndexedWord> adjectives = graph.getChildrenWithReln(child, 
					UniversalEnglishGrammaticalRelations.ADJECTIVAL_MODIFIER); // all adjective modifiers of child
				for (IndexedWord adjective: adjectives)
				{
					String adjLemma = lemmas[sentenceIndex][adjective.index()-1];
					if (adjLemma.equals("other") || adjLemma.equals("opposing"))
						return true;
				}
			}
			//TODO: handle pronouns
		}
		return false;
	}

	/**
	Helper method to parseEndConditions() - given a noun (indexed by nounIndex) in a sentence
	(indexed by sentenceIndex), determines if the noun is "possessed" by any noun phrase denoting "opponent".
	*/
	public boolean isOpponentPossessor(int sentenceIndex, int nounIndex)
	{
		//semantic dependency graph of the sentence
		SemanticGraph graph = sentences.get(sentenceIndex).get(
			SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
		//check for any of the arguments of the verb being any phrase denoting the other player
		IndexedWord nounNode = graph.getNodeByIndexSafe(nounIndex+1); //+1 because SemanticGraph nodes are indexed from 1
		Set<IndexedWord> possessors = null;
		if (nounNode != null) 
			possessors = graph.getChildrenWithReln(nounNode, UniversalEnglishGrammaticalRelations.POSSESSION_MODIFIER);
		for (IndexedWord possessor: possessors)
		{
			String possessorLemma = lemmas[sentenceIndex][possessor.index()-1]; //the lemma of the word possessing the noun
			String possessorPOS = partsOfSpeech[sentenceIndex][possessor.index()-1]; //POS of the word possessing the noun
			//in both of the previous Strings, we subtract 1 from possessor.index() b/c SemanticGraph nodes are indexed from 1
			if (RulesParser.isSynonymOf("opponent", possessorLemma) || possessorLemma.equals("other"))
				return true;
			else if (possessorPOS.charAt(0) == 'N')
			{
				Set<IndexedWord> adjectives = graph.getChildrenWithReln(possessor, 
					UniversalEnglishGrammaticalRelations.ADJECTIVAL_MODIFIER); // all adjective modifiers of child
				for (IndexedWord adjective: adjectives)
				{
					String adjLemma = lemmas[sentenceIndex][adjective.index()-1];
					if (adjLemma.equals("other") || adjLemma.equals("opposing"))
						return true;
				}

			}
			//TODO: handle pronouns?
		}

		return false;
	}

	/**
	Checks if a String (str) is one of the piece names in pieceTypes, or just the word "piece"
	*/
	public boolean isPieceName(String str)
	{
		if (str.equals("piece"))
			return true;
		for (Piece p: pieceTypes)
		{
			if (p.isAnyName(str))
				return true;
		}
		return false;
	}


}