import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import edu.stanford.nlp.dcoref.CorefChain;
import edu.stanford.nlp.dcoref.CorefCoreAnnotations;
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
	private Map<Integer, CorefChain> corefChains;
	private String[][] lemmas; //lemmas[i][j] holds the lemma of the jth word in the ith sentence of the text
	private String[][] partsOfSpeech; //partsOfSpeech[i][j] holds the POS of the jth word in the ith sentence of the text
	//WordNet 3.0 implementation using JAWS:
	private static WordNetDatabase wordnet = WordNetDatabase.getFileInstance();

	private String[][] initialBoard;
	private String[][] transitionZones;
	private ArrayList<String> moveTypes;
	private ArrayList<Piece> pieceTypes;
	private ArrayList<EndCondition> endConditions;


	public RulesParser(String fileName)
	{
		this.fileName = fileName;

		// creates a StanfordCoreNLP object, with sentence splitting, POS tagging, lemmatization, parsing, NER, and coreference resolution
		Properties annotators = new Properties();
		annotators.put("annotators", "tokenize, ssplit, pos, lemma, parse, ner, dcoref");
		pipeline = new StanfordCoreNLP(annotators);
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

		BoardParser boardParser = new BoardParser(this, sentences, lemmas, partsOfSpeech);
		boardParser.parseBoard();
		this.initialBoard = boardParser.getInitialBoard();
		this.transitionZones = boardParser.getTransitionZones();

		PieceParser pieceParser = new PieceParser(this, sentences, lemmas, partsOfSpeech, transitionZones);
		pieceParser.parsePieces();

		this.moveTypes = pieceParser.getMoveTypes();
		this.pieceTypes = pieceParser.getPieceTypes();
		this.transitionZones = pieceParser.getTransitionZones();

		parseEndConditions();
	}

	public ZRFWriter makeZRFWriter()
	{
		ZRFWriter writer = new ZRFWriter(fileName, initialBoard, transitionZones, moveTypes, pieceTypes, endConditions);
		return writer;
	}


	public void parseEndConditions()
	{
		this.endConditions = new ArrayList<EndCondition>(1);

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
			matrixPredicate = isolateIndexFromDependency(firstDep, 2);

			/* We have to iterate over all dependencies two separate times. We first do this to determine the indices
			of all subordinate predicates and negated words. Later, we analyze the other elements of the sentence, 
			using the already-determined subordinate predicates and negated words in our analysis. */
			for (int j = 1; j < dependencies.length; j++)
			{
				String d = dependencies[j];
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
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
				int index1 = isolateIndexFromDependency(d,1);
				int index2 = isolateIndexFromDependency(d,2);
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
						(dominates(i, subordinatePredicates, index1) || subordinatePredicates.contains(index1)))
					{
						isStalemated = true;
					}
					//check for a modifier or argument of the verb being negated within the subordinate clause
					else if (negatedWords.contains(index2) && dominates(i, subordinatePredicates, index2))
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
					if (negatedWords.contains(index2) && dominates(i, subordinatePredicates, index2))
						isStalemated = true;
					/* check for the verb taking it as an argument being negated within the subordinate clause 
					(meaning it is either dominated by a subordinate predicate or is one of them) */
					else if (negatedWords.contains(index1) && 
						(dominates(i, subordinatePredicates, index1) || subordinatePredicates.contains(index1)))
					{
						isStalemated = true;
					}

					/* If either of these checks worked, then isStalemated is true; if so, we now have to check any 
					arguments of the verb taking the noun "move" as an argument and see if they are any phrase denoting the opponent. 
					We do this by calling isOpponentArgument(). (If these are both true, isOppositeType must be set true.) */
					if (isStalemated && isOpponentArgument(i, index1))
						isOppositeType = true;
				}
				//TODO: handle pieces-remaining!

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
			if (isSynonymOf("opponent", childLemma))
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
	Uses CoreNLP's dcoref system to determine the antecedent of an anaphor. Necessarily returns a noun - either returns the head word
	of the NP antecedent, or, if the antecedent is not an NP, returns the first noun in the phrase.
	Returns an empty string if CoreNLP is unable to determine an antecedent for the word.
	*/
	public String determineAntecedent(int sentenceIndex, int wordIndex)
	{
		//sentence indices start at 0 for field "sentences" / for parameter sentenceIndex, but start at 1 for the corefchain
		// word indices start at 0 for parameter wordIndex, but start at 1 for the corefchain
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
					//subtract 1 because the corefchain word indices start at 1 and ours start at 0
					if (mention.headIndex - 1 == wordIndex) // (we test this by comparing their indices in the sentence)
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
	Using CoreNLP's dependency parsing, determines if one word (index1) dominates another word (index2) in the semantic dependency graph 
	of the sentence with index sentenceIndex.
	(This does not actually utilize the parse tree constructed by CoreNLP, since those are usually wrong - instead, it uses the semantic 
	dependency graph, which is not exactly the same nor has exactly the same structure, but the concept of dominance roughly still applies.
	Specifically, if node1 dominates node2 in the semantic graph, then node2 is either directly dependent on node1, or indirectly so, by 
	being dependent on something else that is dependent on node2).
	Returns false if either of the indices are not valid indices in the sentence with index sentenceIndex.
	*/
	public boolean dominates(int sentenceIndex, int index1, int index2)
	{
		SemanticGraph graph = sentences.get(sentenceIndex).get(
			SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);

		/*the words in the SemanticGraph are indexed from 1, but in our system they are indexed from 0 (since we 
		use lemmas[][]); we must therefore add 1 to index1 and index2 when finding their nodes in the graph. */
		IndexedWord node1 = graph.getNodeByIndexSafe(index1+1);
		IndexedWord node2 = graph.getNodeByIndexSafe(index2+1);

		if (node1 != null && node2 != null)
			return graph.getPathToRoot(node2).contains(node1);
		else
			return false;
	}


	/**
	Given a List of Integers indices1, determines whether any of them dominate index2 in the semantic dependency graph. 
	Returns true if any single one of them does, false if none of them do.
	*/
	public boolean dominates(int sentenceIndex, List<Integer> indices1, int index2)
	{
		for (int index1: indices1)
		{
			if (dominates(sentenceIndex, index1, index2))
				return true;
		}
		return false;
	}

	/**
	Using CoreNLP's dependency parsing, determines if one word (index1) is the sibling of another word (index2) in the semantic 
	dependency graph of the sentence with index sentenceIndex.
	(Like dominates(), this method does not actually utilize the parse tree constructed by CoreNLP, since those are usually wrong - 
	instead, it uses the semantic dependency graph, which is not exactly the same nor has exactly the same structure, but the concept
	of sibling constituents roughly still applies.
	Specifically, if node1 and node2 are siblings in the semantic graph, then node2 and node1 are both dependent on the same parent node.
	Eg: in the sentence "the boy kicked the ball", "boy" and "ball" are siblings, being both dependent on "kicked".)
	*/
	public boolean isSibling(int sentenceIndex, int index1, int index2)
	{
		SemanticGraph graph = sentences.get(sentenceIndex).get(
			SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);

		/*the words in the SemanticGraph are indexed from 1, but in our system they are indexed from 0 (since we 
		use lemmas[][]); we must therefore add 1 to index1 and index2 when finding their nodes in the graph. */
		IndexedWord node1 = graph.getNodeByIndexSafe(index1+1);
		IndexedWord node2 = graph.getNodeByIndexSafe(index2+1);

		return graph.getSiblings(node1).contains(node2);
	}

	/**
	Given a List of Integers, determines whether any of them are siblings with index2 in the semantic dependency graph. 
	Returns true if any single one of them is, false if none of them are.
	*/
	public boolean isSibling(int sentenceIndex, List<Integer> indices1, int index2)
	{
		for (int index1: indices1)
		{
			if (isSibling(sentenceIndex, index1, index2))
				return true;
		}
		return false;
	}

	/**
	Given a dependency string of the form: "dependency(word1-index1, word2-index2)",
	this method isolates and returns either index1-1 or index2-1, depending on if whichIndex equals 1 or 2 respectively.
	Decrements the returned index because the indices in the dependency string are indexed from 1, and we wish for them to be 
	indexed from 0.
	Returns -1 if whichWord does not equal 1 or 2.
	*/
	public static int isolateIndexFromDependency(String dependency, int whichIndex)
	{
		int startIndex, endIndex;
		int isolatedIndex;

		switch (whichIndex)
		{
			case 1:
				endIndex = dependency.indexOf(","); //index in dependency string of the comma immediately following the first index
				startIndex = dependency.lastIndexOf("-", endIndex) + 1; //index in dependency string of the first digit of of the first index
				try
				{
					isolatedIndex = Integer.parseInt(dependency.substring(startIndex, endIndex));
					return isolatedIndex-1; // isolatedIndex-1, b/c the dependency strings are indexed from 1 and our system is from 0
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
					return isolatedIndex-1; // isolatedIndex-1, b/c the dependency strings are indexed from 1 and our system is from 0
				}
			case 2: 
				startIndex = dependency.lastIndexOf("-") + 1; //index in dependency string of the first digit of the second index
				endIndex = dependency.indexOf(")"); //index in dependency string of the right parenthesis immediately following second index
				try
				{
					isolatedIndex = Integer.parseInt(dependency.substring(startIndex, endIndex));
					return isolatedIndex-1; // isolatedIndex-1, b/c the dependency strings are indexed from 1 and our system is from 0
				}
				catch (NumberFormatException e)
				{
					while (dependency.charAt(endIndex-1) == '\'')
						endIndex--;
					isolatedIndex = Integer.parseInt(dependency.substring(startIndex, endIndex)); 
					return isolatedIndex-1; // isolatedIndex-1, b/c the dependency strings are indexed from 1 and our system is from 0
				}
			default: //whichIndex can only equal 1 or 2, because a dependency string can only contain 2 tokens (and therefore 2 indices)
				return -1; //error value - index in the sentence can never -1
		}
	}

	/**
	Tests if "first" and "second" are synonyms by seeing if "second" is one of the 
	word forms given in specified WordNet synsets of "first." The parameter "indices"
	specifies the indices of which specific synsets of "first" are to be checked.
	*/
	public static boolean isSynonymOf(String first, String second, int... indices)
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
	public static boolean isHypernymOf(String first, String second)
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
}