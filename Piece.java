import java.util.ArrayList;
import java.util.HashMap;

public class Piece
{
	private String name;
	private ArrayList<Direction> motionTypes;
	private Piece previousType; 
	/* in the case that this type of piece starts out as another type of piece in game play 
	(eg. a king starts out as a checker), this field stores a reference to the initial type of piece.
	if this field is null, this is the default type of piece. */
	private HashMap<Integer,String> transitionSentences; 
	/* keys are indices of the sentences that describe when this piece type becomes another piece type.
	the value associated with a certain key is the name of the piece this piece becomes in that sentence */


	public Piece(String name)
	{
		this.name = name;
		this.previousType = null;
		motionTypes = new ArrayList<Direction>(1);
		transitionSentences = new HashMap<Integer, String>(1);
	}

	public Piece(String name, Piece previousType)
	{
		this.name = name;
		this.previousType = previousType;
		motionTypes = new ArrayList<Direction>(1);
		transitionSentences = new HashMap<Integer, String>(1);
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

	public void addTransitionSentence(Integer index, String pieceName)
	{
		transitionSentences.put(index, pieceName);
	}


	/**
	Determines whether a given sentence index is one of the transition sentences indices stored in transitionSentences.
	*/
	public boolean isTransitionSentence(Integer index)
	{
		return transitionSentences.containsKey(index);
	}

	/**
	Determines whether a given sentence index is one of the transition sentences indices stored in transitionSentences,
	and if so whether it specifically is the transition sentence describing how this piece becomes pieceName.
	*/
	public boolean isTransitionSentence(Integer index, String pieceName)
	{
		if (transitionSentences.containsKey(index))
			return transitionSentences.get(index).equals(pieceName);
		else
			return false;
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