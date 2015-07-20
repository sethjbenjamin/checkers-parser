public class Driver
{
	public static void main(String[] args)
	{
		RulesParser foo = new RulesParser(args[0]);
		foo.parse();
		ZRFWriter writer = new ZRFWriter(args[0]);
		writer.write();
		
	}
}