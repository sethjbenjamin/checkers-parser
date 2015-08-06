import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.semgraph.SemanticGraph;
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

		ArrayList<Integer> endConditionSentences = new ArrayList<Integer>(1);
		for (int i = 0; i < sentences.size(); i++)
		{
			for (String lemma: lemmas[i])
			{
				if (lemma.equals("win") || lemma.equals("lose") || RulesParser.isSynonymOf("tie", lemma, 6) ||
					RulesParser.isSynonymOf("end", lemma))
					endConditionSentences.add(i);
			}
		}

		for (int i: endConditionSentences)
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

			int matrixPredicate = -1;
			ArrayList<Integer> subordinatePredicates = new ArrayList<Integer>(1);
			ArrayList<Integer> negatedWords = new ArrayList<Integer>(1);

			String firstDep = dependencies[0];
			matrixPredicate = RulesParser.isolateIndexFromDependency(firstDep, 2);

			/* We have to iterate over all dependencies two separate times. We first do this to determine the indices
			of all subordinate predicates and negated words. Later, we analyze the other elements of the sentence, 
			using the already-determined subordinate predicates and negated words in our analysis. */
			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = RulesParser.isolateIndexFromDependency(d,1);
				int index2 = RulesParser.isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1];
				String lemma2 = lemmas[i][index2];
				String pos1 = partsOfSpeech[i][index1];
				String pos2 = partsOfSpeech[i][index2];

				if (d.contains("advcl("))
					subordinatePredicates.add(index2);
				else if (d.contains("acl:") || (d.contains("acl(")))
					subordinatePredicates.add(index2);
				else if (d.contains("neg("))
					negatedWords.add(index1);
				else if (d.contains("dep(") && lemma1.equals("not"))
					negatedWords.add(index2);
			}

			//iterate over all dependencies a second time
			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = RulesParser.isolateIndexFromDependency(d,1);
				int index2 = RulesParser.isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1];
				String lemma2 = lemmas[i][index2];
				String pos1 = partsOfSpeech[i][index1];
				String pos2 = partsOfSpeech[i][index2];

				//check for "move" as a verb being negated, or a modifier/argument of it being negated, within the subordinate clause
				if (lemma1.equals("move") && pos1.charAt(0) == 'V')
				{
					/* check for the verb "move" being negated within the subordinate clause 
					(meaning it is either dominated by a subordinate predicate or is one of them) */
					if (negatedWords.contains(index1) && 
						(parent.dominates(i, subordinatePredicates, index1) || subordinatePredicates.contains(index1)))
					{
						isStalemated = true;
					}
					//check for a modifier or argument of the verb being negated within the subordinate clause
					else if (negatedWords.contains(index2) && parent.dominates(i, subordinatePredicates, index2))
						isStalemated = true;

					/* If either of these checks worked, then isStalemated is true; if so, we now have to check any arguments 
					of the verb "move" and see if they are any phrase denoting the opponent. We do this by calling
					isOpponentArgument(). (If these are both true, isOppositeType must be set true.) */
					if (isStalemated && isOpponentArgument(i, index1))
						isOppositeType = true;
						
				}
				/* check for "move" as a noun being negated, or the verb taking it as an argument 
				being negated, within the subordinate clause */
				else if (lemma2.equals("move") && pos2.charAt(0) == 'N') 
				{
					//check for the noun "move" being negated within the subordinate clause
					if (negatedWords.contains(index2) && parent.dominates(i, subordinatePredicates, index2))
						isStalemated = true;
					/* check for the verb taking it as an argument being negated within the subordinate clause 
					(meaning it is either dominated by a subordinate predicate or is one of them) */
					else if (negatedWords.contains(index1) && 
						(parent.dominates(i, subordinatePredicates, index1) || subordinatePredicates.contains(index1)))
					{
						isStalemated = true;
					}

					/* If either of these checks worked, then isStalemated is true; if so, we now have to check any 
					arguments of the verb taking the noun "move" as an argument and see if they are any phrase denoting the opponent. 
					We do this by calling isOpponentArgument(). (If these are both true, isOppositeType must be set true.) */
					if (isStalemated && isOpponentArgument(i, index1))
						isOppositeType = true;
				}
				else if (pos1.charAt(0) == 'V' && (lemma1.equals("capture") || lemma1.equals("remove")))
				{
					if (pos2.charAt(0) == 'N' && isPieceName(lemma2))
					{

					}
					else if (pos2.equals("DT") && lemma2.equals("all"))
					{
						quantifier = 0;
					}
				}

			}

			//determine type of end condition (win, lose, draw)
			if (lemmas[i][matrixPredicate].equals("win"))
			{
				if (isOppositeType)
					isLose = true;
				else
					isWin = true;
			}
			else if (lemmas[i][matrixPredicate].equals("lose"))
			{
				if (isOppositeType)
					isWin = true;
				else
					isLose = true;
			}
			else
			{
				/* TODO: handle other situations, where the matrix pred is not the end type
				(eg: To win, you must capture all the other player's pieces.) */
			}

			//TODO: REMOVE! ALL debugging
			System.out.println("Sentence " + i + " isWin: " + isWin);
			System.out.println("Sentence " + i + " isLose: " + isLose);
			System.out.println("Sentence " + i + " isOppositeType: " + isOppositeType);
			System.out.println("Sentence " + i + " isStalemated: " + isStalemated);

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
		Set<IndexedWord> children = graph.getChildren(predicateNode);
		for (IndexedWord child: children)
		{
			String childLemma = lemmas[sentenceIndex][child.index()-1]; //the lemma of the word each child node represents
			String childPOS = partsOfSpeech[sentenceIndex][child.index()-1]; // POS of the word each child node presents
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

	public boolean isPieceName(String str)
	{
		for (Piece p: pieceTypes)
		{
			if (p.getName().equals(str))
				return true;
		}
		return false;
	}


}