import java.io.*;
import java.util.ArrayList;

public class ZRFWriter
{

	private BufferedWriter writer;
	private String fileName;

	private int numPlayers;
	private int[] dimensions;
	private ArrayList<String> moveTypes;
	private ArrayList<Piece> pieceTypes;

	public ZRFWriter(String fileName)
	{
		if (fileName.contains("/"))
			this.fileName = fileName.substring(fileName.indexOf("/") + 1, fileName.indexOf("."));
		else
			this.fileName = fileName.substring(0, fileName.indexOf("."));

		try
		{
			writer = new BufferedWriter(
				new OutputStreamWriter(
        		new FileOutputStream("zrf/" + this.fileName + ".zrf"), "utf-8")); //output the file as zrf/fileName.zrf
		}
		catch (FileNotFoundException e)
		{
			e.printStackTrace();
		}
		catch (UnsupportedEncodingException e)
		{
			e.printStackTrace();
		}

		numPlayers = 2; //placeholder (TODO)
		//dimensions = new int[2];
		dimensions = new int[]{8,8}; //placeholder (TODO)
	}

	public void write()
	{
		writeHeader();
		writePlayers();
		writeBoard();
		try
		{
			writer.close();
		}
		catch (Exception e)
		{ /* ignore */ }

	}

	public void writeHeader()
	{
		try
		{
			writer.write("(game \n"); 
			writer.write("\t" + "(title \"" + fileName + "\")" + "\n");
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}

	public void writePlayers()
	{
		try
		{
			// write player && turn-order info (p1, p2 ... pN for N players (N = numPlayers))
			writer.write("\t" + "(players");
			for (int i = 1; i <= numPlayers; i++)
				writer.write(" p" + i);
			writer.write(")" + "\n");
			writer.write("\t" + "(turn-order");
			for (int i = 1; i <= numPlayers; i++)
				writer.write(" p" + i);
			writer.write(")" + "\n");
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		//TODO: MOVE PRIORITIES
	}

	public void writeBoard()
	{
		final String alphabet = "abcdefghijklmnopqrstuvwxyz"; // to delineate rows
		try
		{
			writer.write("\t" + "(board" + "\n");
			writer.write("\t\t" + "(grid" + "\n");
			//writer.write("start-rectangle 6 6 55 55"); TODO
			writer.write("\t\t\t" + "(dimensions" + "\n");
			//write rows
			writer.write("\t\t\t\t" + "(\"");
			for (int i = 0; i < dimensions[0]; i++)
			{
				writer.write(alphabet.charAt(i));
				if (i != dimensions[0] - 1)
					writer.write("/");
			}
			writer.write("\") ; rows" + "\n");
			//write columns
			writer.write("\t\t\t\t" + "(\"");
			for (int i = dimensions[1]; i > 0; i--)
			{
				writer.write("" + i);
				if (i != 1)
					writer.write("/");
			}
			writer.write("\") ; columns" + "\n");
			//write directions
			
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}

	/*public void writePieces()
	{
		try
		{
			for (Piece p: pieceTypes)
			{
				String name = p.getName().toUpperCase(); //upper case so the zrf engine doesn't confuse the piece name for a zrf keyword
				writer.write("\t (piece \n");
				writer.write("\t \t (name " + name + ")");
				writer.write("\t \t (moves \n");
				for (String moveType: moveTypes)
				{
					writer.write("\t \t \t ("); 
				}
			}

		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}*/


	/*public void writeEndConditions()
	{
		try
		{

		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}*/


}