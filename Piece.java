import java.util.ArrayList;
import java.util.HashMap;

public class Piece
{
	private String name;
	private Piece previousType; 
	/* in the case that this type of piece starts out as another type of piece in game play 
	(eg. a king starts out as a checker), this field stores a reference to the initial type of piece.
	if this field is null, this is the default type of piece. */
	private ArrayList<String> equivalentTypes; // this list holds other names used in the ruleset to describe this same type of piece
	private ArrayList<Direction> motionTypes; // this list holds the directions of motion in which this piece can move
	private HashMap<Integer,String> transitionSentences; 
	/* keys are indices of the sentences that describe when this piece type becomes another piece type.
	the value associated with a certain key is the name of the piece this piece becomes in that sentence */


	public Piece(String name)
	{
		this.name = name;
		this.previousType = null;
		equivalentTypes = new ArrayList<String>(1);
		motionTypes = new ArrayList<Direction>(1);		
		transitionSentences = new HashMap<Integer, String>(1);
	}

	public Piece(String name, Piece previousType)
	{
		this.name = name;
		this.previousType = previousType;
		equivalentTypes = new ArrayList<String>(1);
		motionTypes = new ArrayList<Direction>(1);
		transitionSentences = new HashMap<Integer, String>(1);
	}

	public String getName()
	{
		return name;
	}

	/**
	Returns a reference to the type of piece that this piece starts out as, or null if this is the default piece.
	*/
	public Piece getPreviousType()
	{
		return previousType;
	}

	/**
	Mutator method for previousType.
	*/
	public void setPreviousType(Piece previousType)
	{
		this.previousType = previousType;
	}

	public void addEquivalentType(String otherName)
	{
		equivalentTypes.add(otherName);
	}

	public void addEquivalentTypes(ArrayList<String> otherNames)
	{
		for (String otherName: otherNames)
		{
			if (!equivalentTypes.contains(otherName))
				equivalentTypes.add(otherName);
		}
	}

	public boolean isEquivalentType(String otherName)
	{
		return equivalentTypes.contains(otherName);
	}

	/**
	Returns a list of the directions of motion this piece can move in.
	*/
	public ArrayList<Direction> getMotionTypes()
	{
		return motionTypes;
	}

	/**
	Given a list of directions, adds each direction to motionTypes if it is not already present.
	*/
	public void addMotionTypes(ArrayList<Direction> newMotionTypes)
	{
		for (Direction d: newMotionTypes)
		{
			if (!motionTypes.contains(d))
				motionTypes.add(d);
		}
	}

	/**
	Given an integer index of a transition sentence, and the name of the transition piece the sentence describes
	this piece becoming, places the index and the piece name in the transitionSentences hashmap.
	*/
	public void addTransitionSentence(int index, String pieceName)
	{
		transitionSentences.put(new Integer(index), pieceName);
	}


	/**
	Determines whether a given sentence index is one of the transition sentences indices stored in transitionSentences.
	*/
	public boolean isTransitionSentence(int index)
	{
		return transitionSentences.containsKey(index);
	}

	/**
	Determines whether a given sentence index is one of the transition sentences indices stored in transitionSentences,
	and if so whether it specifically is the transition sentence describing how this piece becomes pieceName.
	*/
	public boolean isTransitionSentence(int index, String pieceName)
	{
		if (transitionSentences.containsKey(index))
			return transitionSentences.get(index).equals(pieceName);
		else
			return false;
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