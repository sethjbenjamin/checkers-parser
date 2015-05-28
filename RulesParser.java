import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;

import edu.stanford.nlp.io.*;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
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
		ArrayList<Direction> motionTypes = parseMotion();

		/*CoreMap current = sentences.get(8);
		for(CoreMap token: current.get(CoreAnnotations.TokensAnnotation.class))
			System.out.println(token.get(CoreAnnotations.LemmaAnnotation.class));*/

		
	}

	public ArrayList<Direction> parseMotion()
	{
		ArrayList<Direction> motionTypes = new ArrayList<Direction>(1); 
		//ultimately, this ArrayList will hold all of the allowed types of motion explicitly described in the ruleset

		CoreMap current = sentences.get(8); //TODO: figure out the logic behind the actual finding of the sentence(s)!

		//We create an ArrayList of the lemmas of each word in "current", stored as Strings.
		ArrayList<String> lemmas = new ArrayList<String>(1);
		for(CoreMap token: current.get(CoreAnnotations.TokensAnnotation.class)) //iterate over each word
			lemmas.add(token.get(CoreAnnotations.LemmaAnnotation.class)); //add its lemma to the ArrayList
		//Now we create an array of the basic dependencies of "current"
		//the next line simply initializes "dependeciesString" with a String containing all the basic dependencies of "current"
		String dependenciesString = current.get(
			SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class).toString(SemanticGraph.OutputFormat.LIST);
		String[] dependencies = dependenciesString.split("\n"); //split the String into an array, one dependency per entry in the array
		//iterate over all dependencies, searching for certain types
		for (String s: dependencies)
		{
			System.out.println(s);
			if (s.contains("advmod("))
			{
				/* We want to determine if the verb being modified by an adverb is any inflection of the lexeme "move", so we have to
				isolate the number associated with the verb (which is the index of the verb in the sentence) as a substring. */
				int startIndexNum = s.indexOf("-") + 1; //index of the first digit of the number corresponding to the verb being modified
				int endIndexNum = s.indexOf(","); //index of the comma immediately following the number

				int verbIndex = Integer.parseInt(s.substring(startIndexNum,endIndexNum));
				if (lemmas.get(verbIndex-1).equals("move"))
				{
					//We now have to isolate the modifying adverb as a substring.
					int startIndexAdv = s.indexOf(' ') + 1; //index in the first character of the modifying adverb in s
					int endIndexAdv = s.indexOf('-', startIndexAdv); //index of hyphen immediately following modifying adverb in s

					String adverb = s.substring(startIndexAdv,endIndexAdv);
					switch (adverb) //TODO: synonyms of each adverb (use wordnet?)
					{
						case "diagonally":
							motionTypes.add(Direction.DIAGONAL);
							//System.out.println("Diagonally added.");
							break;
						case "forward":
							motionTypes.add(Direction.FORWARD);
							//System.out.println("Forward added.");
							break;
						//TODO: add the other ones (they don't matter right now lol);
					}
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