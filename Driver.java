public class Driver
{
	public static void main(String[] args)
	{
		RulesParser parser = new RulesParser(args[0]);
		parser.parse();
		ZRFWriter writer = parser.makeZRFWriter();
		writer.write();
		
	}
}