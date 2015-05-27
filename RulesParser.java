import java.io.*;
import java.util.List;
import java.util.Properties;

import edu.stanford.nlp.dcoref.CorefChain;
import edu.stanford.nlp.dcoref.CorefCoreAnnotations;
import edu.stanford.nlp.io.*;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.util.*;

public class RulesParser
{
	private String fileName;
	private StanfordCoreNLP pipeline;
	private Annotation annotatedRules;
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
			annotatedRules = new Annotation(IOUtils.slurpFile(fileName));
			pipeline.annotate(annotatedRules);

			sentences = annotatedRules.get(CoreAnnotations.SentencesAnnotation.class);
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
		System.out.println(sentences.get(8).toShorterString());


		
	}

	/*public Direction parseMotion(String s)
	{
		for(CoreMap sentence: sentences) 
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
      	}		

	}*/

	public enum Direction
	{
		FORWARD, BACKWARD, LEFT, RIGHT, DIAGONAL
	}



	
}