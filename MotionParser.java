import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.util.*;


public class MotionParser
{
	private RulesParser parent;
	private List<CoreMap> sentences;
	private String[][] lemmas; //lemmas[i][j] holds the lemma of the jth word in the ith sentence of the text
	private String[][] partsOfSpeech; //partsOfSpeech[i][j] holds the POS of the jth word in the ith sentence of the text
	private ArrayList<String> moveTypes;
	private ArrayList<Piece> pieceTypes;

	public MotionParser(RulesParser parent, List<CoreMap> sentences, String[][] lemmas, 
		String[][] partsOfSpeech, ArrayList<String> moveTypes, ArrayList<Piece> pieceTypes)
	{
		this.parent = parent;
		this.sentences = sentences;
		this.lemmas = lemmas;
		this.partsOfSpeech = partsOfSpeech;
		this.moveTypes = moveTypes;
		this.pieceTypes = pieceTypes;
	}

	public void parseAll()
	{
		for (Piece p: pieceTypes)
		{ 
			ArrayList<Integer> indices = determineMotionSentences(p);
			ArrayList<Direction> motionTypes = parseMotion(p, indices);
			p.addMotionTypes(motionTypes);
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
		- the sentence is not a transition sentence for p (that is, a sentence that describes how p can turn into a different
		  type of piece) (this is determined by calling p.isTransitionSentence(i) where i is the index of the sentence)

		There are a few exceptions:
		- if p is the default type, any sentence containing the noun "move" will be considered a motion sentence
		  (eg: "Only diagonal and forward moves are allowed.")
		- if p is not the default type, any transition sentences that describe how p.previousType becomes p is considered a motion sentence.
		- if the sentence contains a verb that describes motion in the game, with an anaphor as an argument, and the anaphor's antecedent
		  refers not to p but instead to p.previousType, the sentence is considered a motion sentence for p if the sentence containing
		  the anaphor's antecedent is a transition sentence for p.previousType. 
		  (eg: "A checker becomes a king upon reaching the king row. It then can move backward and forward." "it" refers to "checker",
		  but the sentence containing "checker" is a transition sentence for "checker"; therefore, the second sentence
		  is considered a motion sentence for king.)
		*/

		//iterate over all sentences
		for (int i = 0; i < sentences.size(); i++)
		{
			CoreMap sentence = sentences.get(i); //ith (current) sentence

			//dependencies of the current sentence
			String[] dependencies = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class).toString(
				SemanticGraph.OutputFormat.LIST).split("\n");

			boolean isMotionSentence = false;
			int sentenceIndex = -1;

			boolean isNameCompounded = false;
			int compoundedNounIndex = -1;

			//iterate over all dependencies of the current sentence
			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = RulesParser.isolateIndexFromDependency(d,1);
				int index2 = RulesParser.isolateIndexFromDependency(d,2);
				String lemma1 = lemmas[i][index1];
				String lemma2 = lemmas[i][index2];
				String pos1 = partsOfSpeech[i][index1];
				String pos2 = partsOfSpeech[i][index2];

				/* The following if block checks if one of the names of p is a either a modifier or modified in a noun compound. 
				This matters because sometimes  a noun compound, of which the current name of the piece is the modifying noun, is used 
				instead of just the name itself, for example:
				"King pieces can move in both directions, forward and backward." uses the noun compound "king pieces" instead of just 
				saying "king". (CoreNLP parses "king pieces" under the dependency "compound(piece, king)")
				If this is the case, we have to take the modified noun in the compound - in our above example, "pieces" - and store its 
				index; we do this as we will later have to check if the compounded noun is an argument of a motion verb. (If it is,
				we have to treat this the same as if the piece's name itself were an argument.) */
				if (d.contains("compound("))
				{
					if (p.isAnyName(lemma2))
					{
						isNameCompounded = true;
						compoundedNounIndex = index1;
					}
					/* On the other hand, if the modified noun in the noun compound is one of the names of p, we should NOT consider
					this a motion sentence; in our previous example, "King pieces can move in both directions, forward and backward"
					should NOT be considered a motion sentence for p = "piece". */
					else if (p.isAnyName(lemma1))
					{
						isMotionSentence = false;
						break;
					}
				}

				/* The following if statement checks if the current sentence contains one of the parsed motion types as a 
				predicate, and if so, if it either takes name or a pronoun as a subject or direct object. */
				//if (RulesParser.isHypernymOf("move", lemma1) && (d.contains("dobj") || d.contains("nsubj")))
				if (moveTypes.contains(lemma1) && (d.contains("dobj") || d.contains("nsubj")))
				{
					//if p is a transition type, previousType holds its previous type
					Piece previousType = p.getPreviousType();
					//if the argument of the motion predicate is any of the names of p, this is probably a motion sentence
					if (p.isAnyName(lemma2))
					{
						isMotionSentence = true;
						sentenceIndex = i;
					}
					//the following checks if the predicate's argument is a pronoun
					else if (pos2.equals("PRP"))
					{
						String antecedent = parent.determineAntecedent(i, index2); //antecedent of the pronoun

						/* We consider this a motion sentence if the pronoun's antecedent is any of the names
						of p, and if the sentence contaning the antecedent (assumed to be either the current
						sentence or the previous one) is not a transition statement of p. */
						if (p.isAnyName(antecedent) && !p.isTransitionSentence(i) && !p.isTransitionSentence(i-1))
						{
							isMotionSentence = true;
							sentenceIndex = i;
						}
						/* In the case of the following sentences:
						"When a checker reaches the row on the farthest edge from the player, the checker becomes a king. It may 
						then move and jump both diagonally forward and backward."
						the antecedent of "it" is grammatically "checker"; however, the second sentence describes the motion of
						kings, not checkers (that is, it describes the motion of checkers after they become kings.)
						Thus, when the antecedent of a pronoun refers not to p (the piece we are currently parsing the motion of),
						but instead to p's previous type, we have to check if the sentence containing the antecedent (assumed to be
						the previous sentence) is a transition sentence describing how previousType becomes p; 
						if it is, we must still consider the current sentence a motion sentence for p. 
						(If the antecedent is not in the previous sentence but instead is in the current one, and the sentence is
						a transition sentence for previousType, it will already be added below, so we do not need to check for that. */
						else if (previousType != null && previousType.isAnyName(antecedent) && previousType.isTransitionSentence(i-1, name))
						{
							isMotionSentence = true;
							sentenceIndex = i; 
						}
					}
					/* In case of one of the names of currentPiece being a modifier in a noun compound (that is, if 
					isNameCompounded == true), we have to check if the noun it modifies is an argument of a motion verb as well, as this 
					is equivalent to name itself being one. */
					if (isNameCompounded && index2 == compoundedNounIndex)
					{
						isMotionSentence = true;
						sentenceIndex = i;
					}
				}
			}

			/* The following if statement checks p is the default piece, and if so, if the current sentence 
			contains the noun "move". This is because a statement like "Only diagonal moves are allowed." 
			is often used to describe the motion of the default piece. */
			if (p.isDefault())
			{
				//iterate over all lemmas and parts of speech
				for (int j = 0; j < lemmas[i].length; j++)
				{
					if (lemmas[i][j].equals("move") && partsOfSpeech[i][j].charAt(0) == 'N')
					{
						isMotionSentence = true;
						sentenceIndex = i;
					}
				}
			}
			/* If p is not the default piece, it has a previousType. We consider all transition sentences
			that describe how p.previousType becomes p to be potential motion sentences for p.
			Thus, the following else block checks if the current sentence is a transition sentence for p.previousType. */
			else
			{
				Piece previousType = p.getPreviousType();
				if (previousType.isTransitionSentence(i, name))
				{
					isMotionSentence = true;
					sentenceIndex = i;
				}
			}

			/* We only want to add the current sentence's index to indices if:
			- one of the allowed movetypes is a predicate, which takes either name or a pronoun referring to it as an argument,
			  or any of the previously mentioned exceptions is occurring
			  (that is, if isMotionSentence == true)
			- the sentence is not a transition sentence for p */
			if (isMotionSentence && !p.isTransitionSentence(i))
			{
				if (!indices.contains(sentenceIndex)) //also, we don't want to add multiple of the same index
				{
					indices.add(sentenceIndex);
					System.out.println("Motion sentence index for " + name + ": " + sentenceIndex);
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
			/*this ArrayList will hold indices of negated words in the current sentence (those modified by a negation word); 
			if a direction word or the word it modifies is in this list, it will not be added to motionTypes */
			ArrayList<Integer> transitionPiecePredicates = new ArrayList<Integer>(1);
			/*this ArrayList will hold indices of predicates that take as an argument the names of any transition type for p;
			if a direction word modifies a verb, it will not be added to motionTypes if the verb is in this list (as such
			predicates are more likely to denote motion of the transition type, not of p itself) */

			CoreMap sentence = sentences.get(i); //the current sentence

			SemanticGraph graph = sentence.get(
				SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);

			//dependencies for current sentence as a String[], each entry containing a single dependency String
			String[] dependencies = graph.toString(SemanticGraph.OutputFormat.LIST).split("\n");

			//iterate over all dependencies, searching for certain types
			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = RulesParser.isolateIndexFromDependency(d, 1); 
				int index2 = RulesParser.isolateIndexFromDependency(d, 2); 
				String lemma1 = lemmas[i][index1]; 
				String lemma2 = lemmas[i][index2]; 
				String pos1 = partsOfSpeech[i][index1];
				String pos2 = partsOfSpeech[i][index2];

				if (d.contains("neg("))
					negatedWords.add(index1);
				else if (d.contains("dep(") && lemma1.equals("not"))
					negatedWords.add(index2);
				else if (moveTypes.contains(lemma1))
				{
					if (pos2.charAt(0) == 'N' && p.isTransitionType(lemma2))
						transitionPiecePredicates.add(index1);
					else if (pos2.equals("PRP") && p.isTransitionType(parent.determineAntecedent(i,index2)))
						transitionPiecePredicates.add(index1);
				}

				//check for adverbs modifying words
				if (d.contains("advmod("))
				{
					//check if the adverb modifies a verb that is any of the parsed motion types
					if (pos1.charAt(0) == 'V' && moveTypes.contains(lemma1))
					{
						if (!transitionPiecePredicates.contains(index1) && !negatedWords.contains(index1) && !negatedWords.contains(index2))
							addDirection(lemma2, motionTypes, i, name);
					}
					/* CoreNLP is really bad at adverbs: it routinely parses adverbs as modifying nouns, or it mis-parses
					nouns as adjectives and thinks the adverbs are modifying them. To compensate, we also add all directional adverbs that 
					modify any noun or any adjective in a candidate motion  sentence. CoreNLP also parses the following sentence: 
					"Kings move forward and backwards." as having the dependencies "advmod(move, and)", "advmod(and, forward)", 
					"advmod(and, backward)". To compensate, we also add all directional adverbs that modify coordinating conjunctions. */
					else if (pos1.equals("CC") || pos1.charAt(0) == 'N' || pos1.equals("JJ"))
					{
						if (!negatedWords.contains(index1) && !negatedWords.contains(index2))
							addDirection(lemma2, motionTypes, i, name);
					}
					/* CoreNLP also breaks with the construction "move backwards as well as forwards", thinking "backwards" is 
					modfying the adverb "well", so we check for that, too. */
					else if (pos1.equals("RB") && lemma1.equals("well"))
					{
						if (!negatedWords.contains(index1) && !negatedWords.contains(index2))
							addDirection(lemma2, motionTypes, i, name);
					}
				}
				//check for adjectives modifying words
				else if (d.contains("amod("))
				{
					//check for adjectives modifying any of the nouns "move", "direction" or "square"
					if (pos1.charAt(0) == 'N' && (lemma1.equals("move") || lemma1.equals("direction") || lemma1.equals("square")))
					{
						if (!negatedWords.contains(index1) && !negatedWords.contains(index2))
							addDirection(lemma2, motionTypes, i, name);
					}
				}
				else if (d.contains("nmod:toward")) //check for a PP like "toward the opponent"
				{
					// The following checks if the NP complement of the preposition is headed by a synonym of "opponent".
					if (RulesParser.isSynonymOf("opponent", lemma2)) //the object of the preposition is the second word in the dependency
					{
						if (motionTypes.indexOf(Direction.FORWARD) < 0) //TODO: maybe add a negation check
							motionTypes.add(Direction.FORWARD);
						System.out.println("Sentence " + i + ": Forward motion added for " + name + " as a modifying PP"); //debugging
					}
					/* If not, the following checks all dependents on the head of the NP complement of the preposition, searching
					for a phrase denoting the opposite direction: either the adjectives "other", "opposing", or "opposite", or
					any synonym of "opponent". This is for phrases like "toward the other edge" or "toward the opponent's side" */
					else
					{
						IndexedWord noun = graph.getNodeByIndexSafe(index2+1);
						for (IndexedWord dependent: graph.getChildren(noun))
						{
							String dependentLemma = lemmas[i][dependent.index()-1];
							if (RulesParser.isSynonymOf("opponent", dependentLemma) || dependentLemma.equals("opposite") || 
								dependentLemma.equals("other") || dependentLemma.equals("opposing")) 
							{
								if (motionTypes.indexOf(Direction.FORWARD) < 0) //TODO: maybe add a negation check
									motionTypes.add(Direction.FORWARD);
								System.out.println("Sentence " + i + ": Forward motion added for " + name + " as a modifying PP"); //debugging
							}
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
		/*ALL of the following specifications of indices (used when calling RulesParser.isSynonymOf()) are specific 
		to the WordNet 3.0 database!
		They must be changed for future versions of WordNet, as the indices of definitions change. */

		 //5,6 are the indices in Wordnet 3.0 of the definitions of "diagonal" that denote direction
		if (RulesParser.isSynonymOf("diagonal", word, 5, 6) || RulesParser.isSynonymOf("diagonally", word))
		{
			if (motionTypes.indexOf(Direction.DIAGONAL) < 0) //check to see if this type of motion has already been parsed
				motionTypes.add(Direction.DIAGONAL);
			System.out.println("Sentence " + i + ": Diagonal motion added for " + name); //debugging
		}
		//3,6,7,9,11 are the indices in Wordnet 3.0 of the definitions of "forward" that denote direction
		else if (RulesParser.isSynonymOf("forward", word, 3, 6, 7, 9, 11))
		{
			if (motionTypes.indexOf(Direction.FORWARD) < 0) 
				motionTypes.add(Direction.FORWARD);
			System.out.println("Sentence " + i + ": Forward motion added for " + name); //debugging
		}
		//0,2,3 are the indices in Wordnet 3.0 of the definitions of "backward" that denote direction
		else if (RulesParser.isSynonymOf("backward", word, 0, 2, 3))
		{
			if (motionTypes.indexOf(Direction.BACKWARD) < 0)
				motionTypes.add(Direction.BACKWARD);
			System.out.println("Sentence " + i + ": Backward motion added for " + name); //debugging
		}
		//19 is the index in Wordnet 3.0 of the definitions of "left" that denote direction
		else if (RulesParser.isSynonymOf("left", word, 19))
		{
			if (motionTypes.indexOf(Direction.LEFT) < 0)
				motionTypes.add(Direction.LEFT);
			System.out.println("Sentence " + i + ": Leftward motion added for " + name); //debugging
		}
		//12,20 are the indices in Wordnet 3.0 of the definitions of "right" that denote direction
		else if (RulesParser.isSynonymOf("right", word, 12, 20))
		{
			if (motionTypes.indexOf(Direction.RIGHT) < 0)
				motionTypes.add(Direction.RIGHT);
			System.out.println("Sentence " + i + ": Rightward motion added."); //debugging
		}
	}
}