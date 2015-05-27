import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;

//import edu.stanford.nlp.dcoref.CorefChain;
//import edu.stanford.nlp.dcoref.CorefCoreAnnotations;
import edu.stanford.nlp.io.*;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
//import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.util.*;

public class RulesParser
{
	private String fileName;
	private StanfordCoreNLP pipeline;
	private Annotation annotation;
	private List<CoreMap> sentences;


	public RulesParser(String fileName)
	{
		this.fileName = fileName;

		// creates a StanfordCoreNLP object, with sentence splitting, POS tagging, lemmatization, and syntactic dependency parsing
		Properties annotators = new Properties();
		annotators.put("annotators", "tokenize, ssplit, pos, lemma, depparse");
		pipeline = new StanfordCoreNLP(annotators);
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
		/*for (CoreMap sentence: sentences)
		{
			System.out.println(sentence.toShorterString());
		}*/
		//System.out.println(sentences.get(8).toShorterString());
		ArrayList<Direction> motionTypes = parseMotion();

		
	}

	//TODO: EVENTUALLY make this return a Direction
	public ArrayList<Direction> parseMotion()
	{
		/*for(CoreMap sentence: sentences) 
		{
	      	// traversing the words in the current sentence
	      	// a CoreLabel is a CoreMap with additional token-specific methods
	      	for (CoreLabel token: sentence.get(TokensAnnotation.class)) 
	      	{
		        // this is the text of the token
				String word = token.get(TextAnnotation.class);
				// this is the POS tag of the token
				String pos = token.get(PartOfSpeechAnnotation.class);     
	      	}
      	}*/

      	ArrayList<Direction> motionTypes = new ArrayList<Direction>(1);

      	CoreMap current = sentences.get(27); //TODO: automate the actual finding of the sentence!
      	String dependenciesString = current.get(SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class).toString(SemanticGraph.OutputFormat.LIST);
      	String[] dependencies = dependenciesString.split("\n");
		for (String s: dependencies)
		{
			if (s.contains("advmod(move")) //TODO: implement lemmatization - looking for the stem move, not the exact word "move"!
			{
				int startIndex = s.indexOf(' ') + 1;
				int endIndex = s.indexOf('-', startIndex);

				String adverb = s.substring(startIndex,endIndex);
				switch (adverb) //TODO: synonyms of each adverb (use wordnet?)
				{
					case "diagonally":
						motionTypes.add(Direction.DIAGONAL);
						break;
					case "forward":
						motionTypes.add(Direction.FORWARD);
						break;
					//TODO: add the other ones (they don't matter right now lol);
				}
			}
		}
		return motionTypes;
	}

	public enum Direction
	{
		FORWARD, BACKWARD, LEFT, RIGHT, DIAGONAL
	}



	
}