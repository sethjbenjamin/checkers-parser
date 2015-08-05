public class EndCondition
{
	private String type; // either "win", "lose", or "draw"
	private String condition; // either "stalemated" or "pieces-remaining"
	private int quantifier; // quantifier of condition, if a numerical value is attached - eg: pieces-remaining = 0; score = 100; etc

	//type constants
	public static final String WIN = "win";
	public static final String LOSE = "lose";
	public static final String DRAW = "draw";

	//condition constants
	public static final String STALEMATED = "stalemated";
	public static final String PIECES_REMAINING = "pieces-remaining"; //requires a quantifier

	public EndCondition(String type, String condition)
	{
		this.type = type;
		this.condition = condition;
		this.quantifier = -1; //dummy value
	}

	public EndCondition(String type, String condition, int quantifier)
	{
		this.type = type;
		this.condition = condition;
		this.quantifier = quantifier;
	}

	public String getType()
	{
		return type;
	}

	public String getCondition()
	{
		return condition;
	}

	public int getQuantifier()
	{
		return quantifier;
	}

	public boolean hasQuantifier()
	{
		switch (condition)
		{
			case STALEMATED:
				return false;
			case PIECES_REMAINING:
				return true;
			default:
				return false;
		}
	}
}
