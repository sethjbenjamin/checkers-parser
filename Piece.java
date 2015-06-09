import java.util.ArrayList;

public class Piece
{
	private String name;
	private ArrayList<Direction> motionTypes;
	public boolean isTransitionChecked;

	public Piece(String name)
	{
		this.name = name;
		this.isTransitionChecked = false;
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

}