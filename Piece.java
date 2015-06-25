import java.util.ArrayList;

public class Piece
{
	private String name;
	private ArrayList<Direction> motionTypes;
	private Piece previousType; 
	/*in the case that this type of piece starts out as another type of piece in game play 
	(eg. a king starts out as a checker), this field stores a reference to the initial type of piece.
	if this field is null, this is the default type of piece. */
	private ArrayList<Integer> motionSentences; // indices of the sentences that potentially describe the motion of this piece

	public Piece(String name)
	{
		this.name = name;
		this.previousType = null;
		motionTypes = new ArrayList<Direction>(1);
		motionSentences = new ArrayList<Integer>(1);
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

	public void setMotionSentences(ArrayList<Integer> motionSentences)
	{
		this.motionSentences = motionSentences;
	}

	public ArrayList<Integer> getMotionSentences()
	{
		return motionSentences;
	}

	/**
	Returns a reference to the type of piece that this piece starts out as, or null if this is the default piece.
	*/
	public Piece getPreviousType()
	{
		return previousType;
	}

	public void setPreviousType(Piece previousType)
	{
		this.previousType = previousType;
	}

	/**
	Checks if two piece types have the same name.
	*/
	public boolean equals(Piece other)
	{
		return this.name.equals(other.name);
	}

	/**
	Checks if this is the default piece by seeing if previousType is null.
	*/
	public boolean isDefault()
	{
		return previousType == null;
	}

}