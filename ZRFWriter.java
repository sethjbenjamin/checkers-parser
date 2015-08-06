import java.io.*;
import java.util.ArrayList;

public class ZRFWriter
{

	private BufferedWriter writer;
	private String fileName;

	private static final int NUM_PLAYERS = 2; 
	/* number of players is assumed to be 2, as most zrf games are 2-player (human vs. computer) 
	and writing the sections for board symmetry depends on having only 2 players */

	private String[][] initialBoard; //as parsed by BoardParser
	private String[][] transitionZones; //as parsed by BoardParser+PieceParser
	private ArrayList<String> moveTypes; //as parsed by PieceParser
	private ArrayList<Piece> pieceTypes; //as parsed by PieceParser+MotionParser
	private ArrayList<EndCondition> endConditions; //as parsed by RulesParser

	private int[] dimensions;
	private String[][] zrfCoordinates;
	private boolean[][] boardColors; // true is black, false is white

	public ZRFWriter(String fileName, String[][] initialBoard, String[][] transitionZones, 
		ArrayList<String> moveTypes, ArrayList<Piece> pieceTypes, ArrayList<EndCondition> endConditions)
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

		this.initialBoard = initialBoard;
		this.transitionZones = transitionZones;
		this.moveTypes = moveTypes;
		this.pieceTypes = pieceTypes;
		this.endConditions = endConditions;

		this.dimensions = new int[2];
		dimensions[0] = initialBoard.length;
		dimensions[1] = initialBoard[0].length;

		this.zrfCoordinates = new String[dimensions[0]][dimensions[1]];
		this.boardColors = new boolean[dimensions[0]][dimensions[1]];

	}

	public void write()
	{
		try
		{
			writeMoveDefinitions();

			writer.write("(game \n"); // open (game )
			writer.write("\t" + "(title \"" + fileName + "\")" + "\n");

			writePlayers();
			writeBoard();
			writeBoardSetup();
			writePieces();
			writeEndConditions();

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

	public void writeMoveDefinitions()
	{
		//TODO: all of this is placeholders assuming every move to behave exactly like a regular move!
		try
		{
			for (Piece p: pieceTypes)
			{
				String name = p.getName().toUpperCase();
				for (String move: moveTypes)
				{
					//open (define ), (name-move )
					//TODO: this is assuming that you can not move into an occupied space! it already knows!
					writer.write("(define " + name + "-" + move.toUpperCase() + "\t" + "($1 (verify empty?)" + "\n"); 
					int numIfs = 0; //to know how many if statements have to be closed with a right parenthesis
					String alreadyAdded = ""; //so we don't keep adding multiple if statements of the same piece type
					for (String transitionType: p.getTransitionTypes())
					{
						if (!alreadyAdded.contains(transitionType))
						{
							writer.write("\t" + "(if (in-zone? " + transitionType.toUpperCase() + "-transition)" + "\n");
							writer.write("\t\t" + "(add " + transitionType.toUpperCase() + ")" + "\n");
							writer.write("\t" + "else" + "\n");

							alreadyAdded = alreadyAdded + transitionType + " ";
							numIfs++;
						}
					}
					writer.write ("\t\t" + "add" + "\n"); 
					writer.write ("\t");
					for (int i = 0; i < numIfs; i++)
						writer.write (")"); // close all the if's detailing transition zones
					writer.write("\n");
					writer.write("))" + "\n"); // close (name-move ) and (define ) 

					writer.newLine();
				}
			}
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

			/* initialize zrfCoordinates[][] with the board coordinates used in the zrf, 
			and boardColors[][] with the associated color values */
			for (int i = 0; i < dimensions[0]; i++)
			{
				for (int j = 0; j < dimensions[1]; j++)
				{
					zrfCoordinates[i][j] = String.valueOf(alphabet.charAt(dimensions[1]-j-1)) + (i+1);
					if ((i+j) % 2 == 0) // alternating black and white pattern on checkerboard
						boardColors[i][j] = false;
					else
						boardColors[i][j] = true;
				}
			}
			/*at this point, zrfCoordinates is a 2d string array containing all the individual coordinates of each square on the
			checkerboard, and boardColors is a 2d boolean array containing the individual colors of each square on the board
			(false = white, true = black) */

			//write directions
			writer.write("\t\t\t" + "(directions" + "\n"); // open (directions )
			writer.write("\t\t\t\t" + "(n 0 -1) (w -1 0) (s 0 1) (e 1 0)" + "\n"); //TODO: is this necessary or helpful at all?
			writer.write("\t\t\t\t" + "(ne 1 -1) (nw -1 -1) (se 1 1) (sw -1 1)" + "\n");
			//writer.write("\t\t\t\t" + "(nn 0 -2) (ww -2 0) (ss 0 2) (ee 2 0)"); 
			// TODO: this might be assuming the adjacentness of move and jump! look up chess

			writer.write("\t\t\t" + ")" + "\n"); // close (directions )
			writer.write("\t\t" + ")" + "\n"); //close (grid )

			//write symmetry
			//all of this block is dependent on NUM_PLAYERS being 2! if NUM_PLAYERS != 2 this no longer works
			writer.write("\t\t" + "(symmetry P2 (n s) (s n) (ne sw) (sw ne) (nw se) (se nw))" + "\n");

			//write transition zones
			//iterate over all piece types
			for (Piece p: pieceTypes)
			{
				String name = p.getName(); //name of piece
				if (!p.isDefault()) //if piece is a transition piece,
				{
					//iterate over all players (each player has separate transition zone)
					for (int playerNum = 1; playerNum <= NUM_PLAYERS; playerNum++)
					{
						writer.write("\t\t" + "(zone (name " + name.toUpperCase() + "-transition" + ")"); //open (zone )
						writer.write(" (players P" + playerNum + ")" + "\n"); // open and close (players )
						writer.write("\t\t\t" + "(positions"); //open (positions )
						String transitionTrigger = "P" + playerNum + "-" + name; 
						/* eg: "P2-king", meaning a square is part of the transition zone for P2's 
						piece to become a king - this is how zones are stored in transitionZone */
						//iterate over all squares in transitionZones, comparing their value to transitionTrigger
						for (int i = 0; i < transitionZones.length; i++)
						{
							for (int j = 0; j < transitionZones[i].length; j++)
							{
								String square = transitionZones[i][j];
								if (square != null && square.contains(transitionTrigger))
									writer.write(" " + zrfCoordinates[i][j]);
							}
						}
						writer.write(")" + "\n"); // close (positions )
						writer.write("\t\t" + ")" + "\n"); //close (zone )
					}
				}
			}

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
			String defaultName = defaultPiece.getName().toUpperCase(); //uppercase b/c we define pieces w/ uppercase names later in the zrf
			writer.write("\t" + "(board-setup" + "\n"); //open (board-setup )

			for (int playerNum = 1; playerNum <= NUM_PLAYERS; playerNum++)
			{
				writer.write("\t\t" + "(P" + playerNum + " (" + defaultName);
				//using initialBoard, determine what the zrf-coordinates of the initial positions of each piece are

				for (int i = 0; i < initialBoard.length; i++)
				{
					for (int j = 0; j < initialBoard[i].length; j++)
					{
						String square = initialBoard[i][j]; // a single square on initialBoard
						//square will either hold "null" or "P1", "P2," etc - so we extract the number and compare it to playerNum
						if (square != null)
						{
							int squareNumber = Integer.parseInt(square.substring(square.lastIndexOf("P")+1));
							if (playerNum == squareNumber)
								writer.write(" " + zrfCoordinates[i][j]);
						}
					}
				}

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
				for (String move: moveTypes)
				{
					writer.write("\t\t\t" + "(move-type " + move.toUpperCase() + ")" + "\n");
					ArrayList<Direction> motionTypes = p.getMotionTypes();
					//TODO: this needs to be expanded!! also, MotionParser needs to actually learn that diagonal is exclusive
					if (motionTypes.contains(Direction.DIAGONAL) && motionTypes.contains(Direction.FORWARD))
					{
						writer.write("\t\t\t" + "(" + name + "-" + move.toUpperCase() + " nw" + ")" + "\n");
						writer.write("\t\t\t" + "(" + name + "-" + move.toUpperCase() + " ne" + ")" + "\n");
					}
					if (motionTypes.contains(Direction.DIAGONAL) && motionTypes.contains(Direction.BACKWARD))
					{
						writer.write("\t\t\t" + "(" + name + "-" + move.toUpperCase() + " sw" + ")" + "\n");
						writer.write("\t\t\t" + "(" + name + "-" + move.toUpperCase() + " se" + ")" + "\n");
					}
					writer.newLine(); 
				}
				writer.write("\t\t" + ")" + "\n"); // close (moves )
				writer.write("\t" + ")" + "\n"); // close (piece )

			}
			writer.newLine();

		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}


	public void writeEndConditions()
	{
		try
		{
			for (EndCondition ec: endConditions)
			{
				writer.write("\t" + "("); // open (end-condition )
				String type = ec.getType(); // have to determine what type of end condition this is (win, lose, draw)
				if (type.equals(EndCondition.WIN)) // type is win
					writer.write("win");
				else if (type.equals(EndCondition.LOSE)) // type is lose
					writer.write("loss");
				else // type is draw
					writer.write("draw");
				writer.write("-condition ("); // open (P1 P2 )

				// The following assumes the end conditions are the same for all players; that is a limitation of this system
				for (int i = 1; i <= NUM_PLAYERS; i++)
					writer.write("P" + i + " "); 
				writer.write(") "); // close (P1 P2 )
				writer.write(ec.getCondition() + " "); // write the condition (either stalemated or pieces-remaining)
				if (ec.hasQuantifier()) // if this end condition has a quantifier (like pieces-remaining 0)
					writer.write(ec.getQuantifier()); // write it
				writer.write(")" + "\n");
			}

			writer.newLine();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}


}