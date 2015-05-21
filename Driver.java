public class Driver
{
	public static void main(String[] args)
	{
		RulesParser foo = new RulesParser(args[0]);
		foo.parse();
	}
}