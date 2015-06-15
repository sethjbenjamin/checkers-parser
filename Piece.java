import java.util.ArrayList;

public class Piece
{
	private String name;
	private ArrayList<Direction> motionTypes;
	private Piece previousType; 
	/*in the case that one type of piece becomes another type of piece after something happens, 
	this field stores a reference to the first type of piece. */

	public Piece(String name)
	{
		this.name = name;
		this.previousType = null;
		motionTypes = new ArrayList<Direction>(1);
	}

	public Piece(String name, Piece previousType)
	{
		this.name = name;
		this.previousType = previousType;
		motionTypes = new ArrayList<Direction>(1);
	}

	public String getName()
	{
		return name;
	}

	public ArrayList<Direction> getMotionTypes()
	{
		return motionTypes;
	}

	public void addMotionTypes(ArrayList<Direction> newMotionTypes)
	{
		for (Direction d: newMotionTypes)
		{
			motionTypes.add(d);
		}
	}

	/**
	Checks if two piece types have the same name.
	*/
	public boolean equals(Piece other)
	{
		return this.name == other.name;
	}

	public Piece getPreviousType()
	{
		return previousType;
	}

}