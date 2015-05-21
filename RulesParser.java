import java.io.*;
import java.util.ArrayList;

public class RulesParser
{
	private String fileName;
	private ArrayList<String[]> rules;

	private int numPlayers;
	private int[] dimensions;

	private static final int NON_INTEGER = -1; //arbitrary dummy value - returned by toInteger when passed a non-number

	public RulesParser(String fileName)
	{
		this.fileName = fileName;
		rules = new ArrayList<String[]>();
	}

	public void readFile()
	{
		try
		{
			BufferedReader in = new BufferedReader(
				new FileReader(
				new File(fileName)));

			String current = null;
			while ((current = in.readLine()) != null)
			{
				String[] wordsInCurrent = current.split(" ");
				rules.add(wordsInCurrent);
			}
			/* "rules" is an ArrayList of String[]'s. 
			After this block, every String[] "sa" in "rules" contains a single line of text from the original text file.
			Every String "s" in one of these String[]'s contains a single word. This structure is done so individual words in a single
			line can be compared with each other easily. The assumption is that line breaks in the original text roughly correlate
			with topic changes in the rule set.
			*/

		}
		catch (IOException e)
		{
			e.printStackTrace();
		}

		/* 
		//debugging:
		for (String[] strArr: rules)
		{
			for (String str: strArr)
			{
				System.out.print(str + " ");
			}
			System.out.println();
		}*/
	}

	public void parse()
	{
		readFile();
		//issue with the separation of this into separate methods: inefficient?
		//a lot of excessive iteration that is maybe fixable
		numPlayers = determineNumPlayers();
		dimensions = determineGrid();

	}

	public int determineNumPlayers()
	{
		for (String[] line: rules)
		{
			for (int i = 0; i < line.length-1; i++)
			{
				//first: test for a sequence like "n players" or "n people"
				String word = line[i];
				if (toInteger(word) != NON_INTEGER) //if word is a number,
				{
					String nextWord = line[i+1];
					if (nextWord.contains("players") || nextWord.contains("people"))
						return toInteger(word);
				}

				//next: test for a sequence like "n-player" or "n-person"
				int hyphenIndex = word.indexOf("-"); // -1 if there's no hyphen, > -1 if there is
				if (hyphenIndex > -1)
				{
					String preHyphen = word.substring(0, hyphenIndex); //substring before the hyphen
					String postHyphen = word.substring(hyphenIndex+1, word.length()); //substring after the hyphen

					//if preHyphen is a number and postHyphen is either "player" or "person", return the number
					if (toInteger(preHyphen) != NON_INTEGER && (postHyphen.contains("player") || postHyphen.contains("person")))
					{
						return toInteger(preHyphen);
					}
				}
			}
		}
		return 2; // if no explicit mention, assume 2 players
	}

	public int[] determineGrid()
	{
		int[] dimensions = new int[]{0,0};
		int area = 0;
		for (String[] line: rules)
		{
			for (int i = 0; i < line.length; i++)
			{
				//first: test for a word of form "MxN" or "MXN" where M,N are integers
				String word = line[i];
				if (word.contains("x") || word.contains("X"))
				{
					int xIndex = Math.max(word.indexOf("x"), word.indexOf("X")); 
					//xIndex is now whichever index is not -1 - the word cannot feasibly contain both X and x

					String preX = word.substring(0, xIndex); //substring before the x
					String postX = word.substring(xIndex+1, word.length()); //substring after the x

					//if preX and postX are both numbers, then the word likely denotes dimensions
					if (toInteger(preX) != NON_INTEGER && toInteger(postX) != NON_INTEGER)
					{
						dimensions = new int[]{toInteger(preX), toInteger(postX)};
						return dimensions;
					}
				}


				/*next: 
				-test for a sequence like "N [...] squares", ignoring words in between. 
					-If multiple such sequences are found in the same line, 
					the one with largest N is taken to be the dimensions of the complete board. 
				-test for sequence like "N [...] columns" and "N [...] rows" similarly ignoring words in between*/
				if (toInteger(word) != NON_INTEGER) //if word is a number,
				{
					for (int j = i+1; j < line.length; j++)
					{
						String laterWord = line[j];
						if (laterWord.contains("squares"))
						{
							area = Math.max(toInteger(word), area);
							dimensions = new int[]{(int)Math.sqrt(area), (int)Math.sqrt(area)};
							break;
						}
						else if (laterWord.contains("columns"))
						{
							dimensions[0] = toInteger(word);
							if (dimensions[1] != 0) //if we've already found the number of rows
								return dimensions;
							break;

						}
						else if (laterWord.contains("rows"))
						{
							dimensions[1] = toInteger(word);
							if (dimensions[0] != 0) //if we've already found the number of columns
								return dimensions;
							break;
						}
					}
				}

			}
			if (area > 0) // a sequence like "N squares" was found; returning it here ensures the maximum N for a given line is chosen
				return dimensions;
		}

		dimensions = new int[]{8,8}; // if there's no mention of the board size, assume 8x8 (standard checkerboard)
		return dimensions;
	}

	public static int toInteger(String s)
	{
		try
		{
			/* if we try to call Integer.parseInt(s), one of the following will happen:
				-it'll work, if s contains a integer written in digits, or
				-it'll throw a NumberFormatException, if s contains something else. */
			return Integer.parseInt(s);
		}
		catch(NumberFormatException e)
		{
			/*If we're here, s doesn't contain an integer written in digits; 
			we now have to check if s is a linguistic numeral (eg, "sixty-four"). */
			int converted = NON_INTEGER; //dummy value, this is only going to be used to return positive, relatively small values
			//the following switch block handles linguistic numerals from 0-99
			switch (s.toLowerCase())
			{
				case "zero":
					converted = 0;
					break;
				case "one":
					converted = 1;
					break;
				case "two":
					converted = 2;
					break;
				case "three":
					converted = 3;
					break;
				case "four":
					converted = 4;
					break;
				case "five":
					converted = 5;
					break;
				case "six":
					converted = 6;
					break;
				case "seven":
					converted = 7;
					break;
				case "eight":
					converted = 8;
					break;
				case "nine":
					converted = 9;
					break;
				case "ten":
					converted = 10;
					break;
				case "eleven":
					converted = 11;
					break;
				case "twelve":
					converted = 12;
					break;
				case "thirteen":
					converted = 13;
					break;
				case "fourteen":
					converted = 14;
					break;
				case "fifteen":
					converted = 15;
					break;
				case "sixteen":
					converted = 16;
					break;
				case "seventeen":
					converted = 17;
					break;
				case "eighteen":
					converted = 18;
					break;
				case "nineteen":
					converted = 19;
					break;
				case "twenty":
					converted = 20;
					break;
				case "thirty":
					converted = 30;
					break;
				case "forty":
					converted = 40;
					break;
				case "fifty":
					converted = 50;
					break;
				case "sixty":
					converted = 60;
					break;
				case "seventy":
					converted = 70;
					break;
				case "eighty":
					converted = 80;
					break;
				case "ninety":
					converted = 90;
				default:
					if (s.contains("-"))
					{
						int hyphenIndex = s.indexOf("-");
						String preHyphen = s.substring(0, hyphenIndex); //substring before the hyphen
						String postHyphen = s.substring(hyphenIndex+1, s.length()); //substring after the hyphen

						/*s is an integer if both preHyphen and postHyphen are integers (for example: in sixty-four,
						both "sixty" and "four" are integers individually*/
						if (toInteger(preHyphen) != NON_INTEGER && toInteger(postHyphen) != NON_INTEGER)
							converted = toInteger(preHyphen) + toInteger(postHyphen);
					}
			}
			return converted;
		}
	}
}