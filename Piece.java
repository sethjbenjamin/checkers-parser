import java.util.ArrayList;

public class Piece
{
	private String name;
	private ArrayList<Direction> motionTypes;

	public Piece(String name)
	{
		this.name = name;
	}

	public String getName()
	{
		return name;
	}

	public ArrayList<Direction> getMotionTypes()
	{
		return motionTypes;
	}

	public void setMotionTypes(ArrayList<Direction> newMotionTypes)
	{
		motionTypes = newMotionTypes;
	}

	/**
	Checks if two piece types have the same name.
	*/
	public boolean equals(Piece other)
	{
		return this.name == other.name;
	}


}