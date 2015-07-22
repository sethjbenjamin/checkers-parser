import java.io.*;
import java.util.ArrayList;

public class ZRFWriter
{

	private BufferedWriter writer;
	private String fileName;

	private static final int NUM_PLAYERS = 2; 
	/* number of players is assumed to be 2, as most zrf games are 2-player (human vs. computer) 
	and writing the sections for board symmetry depends on having only 2 players */

	private int[] dimensions; //dimensions[0] is the number of rows, dimensions[1] is the number of columns
	private String[][] board;
	private boolean[][] boardColors; // true is black, false is white
	private int numInitialRows; // the number of rows each player initially fills with pieces
	private int numInitialPieces; //the number of pieces each player initially starts with
	private ArrayList<String> moveTypes;
	private ArrayList<Piece> pieceTypes;

	public ZRFWriter(String fileName, ArrayList<String> moveTypes, ArrayList<Piece> pieceTypes)
	{
		if (fileName.contains("/"))
			this.fileName = fileName.substring(fileName.lastIndexOf("/") + 1, fileName.lastIndexOf("."));
		else
			this.fileName = fileName.substring(0, fileName.lastIndexOf("."));

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

		//dimensions = new int[2];
		dimensions = new int[]{8,8}; //placeholder (TODO)
		numInitialRows = 3; //placeholder (TODO)
		numInitialPieces = 12; //placeholder (TODO)
		board = new String[dimensions[0]][dimensions[1]];
		boardColors = new boolean[dimensions[0]][dimensions[1]];

		this.moveTypes = moveTypes;
		this.pieceTypes = pieceTypes;
	}

	public void write()
	{
		try
		{
			writer.write("(game \n"); // open (game )
			writer.write("\t" + "(title \"" + fileName + "\")" + "\n");

			writePlayers();
			writeBoard();
			writeBoardSetup();
			writePieces();

			writer.write(")"); // close (game )
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}

		// all the ZRF is written, try to close the BufferedWriter and ignore exceptions
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
			// write player && turn-order info (P1, P2... PNUM_PLAYERS)
			writer.write("\t" + "(players");
			for (int i = 1; i <= NUM_PLAYERS; i++)
				writer.write(" P" + i);
			writer.write(")" + "\n");
			writer.write("\t" + "(turn-order");
			for (int i = 1; i <= NUM_PLAYERS; i++)
				writer.write(" P" + i);
			writer.write(")" + "\n");

			writer.newLine();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		//TODO: MOVE PRIORITIES
	}

	public void writeBoard()
	{
		final String alphabet = "abcdefghijklmnopqrstuvwxyz"; // to delineate columns
		try
		{
			writer.write("\t" + "(board" + "\n"); // open (board )
			writer.write("\t\t" + "(grid" + "\n"); // open (grid )
			//writer.write("start-rectangle 6 6 55 55"); TODO - is this necessary?

			writer.write("\t\t\t" + "(dimensions" + "\n"); // open (dimensions )
			//write columns to zrf
			writer.write("\t\t\t\t" + "(\"");
			for (int j = 0; j < dimensions[1]; j++)
			{
				writer.write(alphabet.charAt(j));
				if (j != dimensions[0] - 1)
					writer.write("/");
			}
			writer.write("\") ; columns" + "\n");
			//write rows to zrf
			writer.write("\t\t\t\t" + "(\"");
			for (int i = dimensions[0]; i > 0; i--) // in the zrf, the rows count downwards to 1
			{
				writer.write("" + i);
				if (i != 1)
					writer.write("/");
			}
			writer.write("\") ; rows" + "\n");
			writer.write("\t\t\t" + ")" + "\n"); //close (dimensions )

			/* initialize board[][] with the board coordinates used in the zrf, 
			and boardColors[][] with the associated color values */
			for (int i = 0; i < dimensions[0]; i++)
			{
				for (int j = 0; j < dimensions[1]; j++)
				{
					board[i][j] = String.valueOf(alphabet.charAt(dimensions[1]-j-1)) + (i+1);
					if ((i+j) % 2 == 0) // alternating black and white pattern on checkerboard
						boardColors[i][j] = false;
					else
						boardColors[i][j] = true;
				}
			}
			/*at this point, board is a 2d string array containing all the individual coordinates of each square on the
			checkerboard, and boardColors is a 2d boolean array containing the individual colors of each square on the board
			(false = white, true = black) */

			//write directions
			writer.write("\t\t\t" + "(directions" + "\n"); // open (directions )
			writer.write("\t\t\t\t" + "(n 0 -1) (w -1 0) (s 0 1) (e 1 0)" + "\n"); //TODO: is this necessary or helpful at all?
			writer.write("\t\t\t\t" + "(ne 1 -1) (nw -1 -1) (se 1 1) (sw -1 1)" + "\n");
			//writer.write("\t\t\t\t" + "(nn 0 -2) (ww -2 0) (ss 0 2) (ee 2 0)"); 
			// TODO: this might be assuming the defintions of move and jump! look up chess

			writer.write("\t\t\t" + ")" + "\n"); // close (directions )
			writer.write("\t\t" + ")" + "\n"); //close (grid )

			//write symmetry
			//all of this block is dependent on NUM_PLAYERS being 2! if NUM_PLAYERS != 2 this no longer works
			writer.write("\t\t" + "(symmetry P2 (n s) (s n) (ne sw) (sw ne) (nw se) (se nw))" + "\n");

			//TODO: write zones! (after rules parser shows it is capable of figuring them out in any way)

			writer.write("\t" + ")" + "\n"); //close (board )

			writer.newLine();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}

	public void writeBoardSetup()
	{
		try
		{
			Piece defaultPiece = null; 
			for (Piece p: pieceTypes)
			{
				if (p.isDefault())
				{
					defaultPiece = p;
					break;
				}
			}
			String defaultName = defaultPiece.getName().toUpperCase(); //uppercase because we define pieces with uppercase names later in the zrf
			writer.write("\t" + "(board-setup" + "\n"); //open (board-setup )

			for (int playerNum = 1; playerNum <= NUM_PLAYERS; playerNum++)
			{
				writer.write("\t\t" + "(P" + playerNum + " (" + defaultName + " ");
				//now we must determine what the initial positions of all the pieces are

				String[] initialPositions = new String[0]; 
				/* initialized with length 0 so that if neither numInitialRows nor numInitialPieces are greater than zero
				(meaning RulesParser was unable to determine any initial positions), the program doesn't crash when we later
				try to iterate over initialPositions */
				int i = 0;

				int row, column;
				if (playerNum == 1) //player 1
					row = 0; //player 1's pieces start at the top and are filled downward
				else //player 2 (depends on NUM_PLAYERS being 2 - if greater, the remaining player's pieces will be on top of player 2's pieces)
					row = dimensions[0]-1; //player 2's pieces start at the bottom and are filled upward
				column = 0;

				/* All of the following blocks determinning initial positions of pieces are dependent on NUM_PLAYERS equaling 2!
				They will misbehave if NUM_PLAYERS is greater than 2. */
				if (numInitialRows > 0)
				{
					int numRowsFilled = 0; //the number of rows we have currently filled
					initialPositions = new String[ numInitialRows * dimensions[0] / 2 ]; //TODO - hard coded with knowledge of only dark squares
					while (numRowsFilled < numInitialRows)
					{
						//TODO - the following if statement is just hard-coded with the knowledge that the game happens only on dark squares!
						if (boardColors[row][column]) // if this is a dark square,
						{
							initialPositions[i] = board[row][column]; // consider it one of the initial positions
							i++; // increment the running index of initialPositions
						}
						column++; //advance to the next column 
						if (column >= dimensions[1]) // if we're out of columns, 
						{
							if (playerNum == 1) // for player 1,
								row++; //move down to the next row
							else // for player 2,
								row--; //move up to the previous row
							column = 0; //reset to first column of new row
							numRowsFilled++;
						}
					}
				}
				else if (numInitialPieces > 0)
				{
					initialPositions = new String[numInitialPieces];
					while (i < numInitialPieces)
					{	
						//TODO - the following if statement is just hard-coded with the knowledge that the game happens only on dark squares!
						if (boardColors[row][column]) // if this is a dark square,
						{
							initialPositions[i] = board[row][column]; // consider it one of the initial positions
							i++; // increment the running index of initialPositions
						}
						column++; //advance to the next column 
						if (column >= dimensions[1]) // if we're out of columns, 
						{
							if (playerNum == 1) // for player 1,
								row++; //move down to the next row
							else // for player 2,
								row--; //move up to the previous row
							column = 0; //reset to first column of new row
						}
					}
				}

				for (String position: initialPositions)
					if (position != null)
						writer.write(position + " ");
				writer.write(") )" + "\n");
			}
			writer.write("\t" + ")" + "\n"); // close (board-setup)

			writer.newLine();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}

	public void writePieces()
	{
		try
		{
			for (Piece p: pieceTypes)
			{
				String name = p.getName().toUpperCase(); //upper case so the zrf engine doesn't confuse the piece name for a zrf keyword
				writer.write("\t" + "(piece" + "\n"); //open (piece )
				writer.write("\t\t" + "(name " + name + ")" + "\n"); //write the piece's name to zrf
				//write the piece's moves to zrf
				writer.write("\t\t" + "(moves" + "\n"); // open (moves )
				for (String moveType: moveTypes)
				{
					writer.write("\t\t\t" + "(move-type " + moveType.toUpperCase() + ")" + "\n");
					//TODO: HANDLE DIRECTIONS!

					writer.newLine(); 
				}
				writer.write("\t\t" + ")" + "\n"); // close (moves )
				writer.write("\t" + ")" + "\n"); // close (piece )

			}

		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}


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