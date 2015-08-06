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

	public void parseEndConditions()
	{
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
					else if (pos.charAt(0) == 'V' && RulesParser.isSynonymOf("end", lemma))
						endConditionSentences.put(i, EndCondition.LOSE);
					else if (RulesParser.isSynonymOf("tie", lemma, 6))
						endConditionSentences.put(i, EndCondition.DRAW); 
				}
			}
		}

		for (int i: endConditionSentences.keySet())
		{
			//current sentence
			CoreMap sentence = sentences.get(i);
			//semantic dependency graph of current sentence
			SemanticGraph graph = sentence.get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
			//dependencies for current sentence as a String[], each entry containing a single dependency String
			String[] dependencies = graph.toString(SemanticGraph.OutputFormat.LIST).split("\n");

			boolean isWin = false;
			boolean isLose = false;
			boolean isDraw = false;

			boolean isOppositeType = false;

			boolean isStalemated = false;
			boolean isPiecesRemaining = false;

			int quantifier = -1;

			ArrayList<Integer> negatedWords = new ArrayList<Integer>(1);
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

					/* If either of these checks worked, then isStalemated is true; if so, we now have to check any arguments 
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
				else if (pos1.charAt(0) == 'V' && pos2.equals("DT") && lemma2.equals("all"))
				{
					//check for a phrase like "all of the pieces" as the argument of the predicate			
					quantifier = 0;
					IndexedWord node2 = graph.getNodeByIndexSafe(index2+1);
					Set<IndexedWord> children = null;
					if (node2 != null)
						children = graph.getChildren(node2);
					if (children != null)
					{
						for (IndexedWord child: children)
						{
							int childIndex = child.index()-1;
							if (partsOfSpeech[i][childIndex].charAt(0) == 'N' && isPieceName(lemmas[i][childIndex]))
							{
								if (lemma1.equals("capture") || lemma1.equals("remove") || lemma1.equals("lose"))
									isPiecesRemaining = true;
								else if (RulesParser.isSynonymOf("block", lemma1))
									isStalemated = true;

								if ((isPiecesRemaining || isStalemated) && isOpponentPossessor(i, childIndex))
									isOppositeType = true;
							}
						}
					}
				}
			}

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
			System.out.println("Sentence " + i + " isWin: " + isWin);
			System.out.println("Sentence " + i + " isLose: " + isLose);
			System.out.println("Sentence " + i + " isOppositeType: " + isOppositeType);
			System.out.println("Sentence " + i + " isStalemated: " + isStalemated);
			System.out.println("Sentence " + i + " isPiecesRemaining: " + isPiecesRemaining);

			if (isWin)
			{
				if (isStalemated)
				{
					endConditions.add(new EndCondition(EndCondition.WIN, EndCondition.STALEMATED));
					System.out.println("Win condition parsed: stalemated in sentence " + i);
				}
				if (isPiecesRemaining)
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
				if (isPiecesRemaining)
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
				if (isPiecesRemaining)
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
			if (p.getName().equals(str))
				return true;
		}
		return false;
	}


}