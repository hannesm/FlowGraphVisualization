import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;


public class LayouterClient extends Thread {
	private Socket socket;
	private BufferedReader reader;
	private PrintWriter writer;
	private HashMap<String, IncrementalHierarchicLayout> graphs = new HashMap<String, IncrementalHierarchicLayout>();
	public boolean active = true;
	
	public LayouterClient (Socket s) {
		try {
			socket = s;
			reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public IncrementalHierarchicLayout getGraph (String index) {
		return graphs.get(index);
	}
	
	public int getGraphSize () {
		return graphs.size();
	}
	
	private ArrayList readMessage () throws NumberFormatException, IOException {
		char[] buffer = new char[6];
		if (reader.read(buffer, 0, 6) == 6) {
			String size = "";
			for (int i = 0; i < 6; i++)
				size += Character.toString(buffer[i]);
			int message_length = Integer.parseInt(size, 16);
			return read_s_expression(message_length);
		}
		return null;
	}
	
	public IncrementalHierarchicLayout findComparableGraph (String graphname, boolean finished) {
		//first try active sessions
		for (LayouterClient l : FlowGraphVisualizer.clients)
			if (l != this && l.active && l.getGraph(graphname) != null && l.getGraph(graphname).graphfinished == finished)
				return l.getGraph(graphname);
		//now try also inactive sessions
		for (LayouterClient l : FlowGraphVisualizer.clients)
			if (l != this && l.getGraph(graphname) != null && l.getGraph(graphname).graphfinished == finished)
				return l.getGraph(graphname);
		//if we found no matching graph, return null...
		return null;
	}
	
	public void run() {
		try {
			ArrayList answer = readMessage();
			assert(answer.size() == 2);
			assert(answer.get(0) instanceof Symbol);
			assert(answer.get(1) instanceof Symbol);
			assert(((Symbol)answer.get(0)).isEqual("connection-identifier"));
			Symbol identifier = (Symbol)answer.get(1);
			ArrayList result = new ArrayList();
			result.add(new Symbol("ok"));

			printMessage(result);
			
			DemoBase demo = new DemoBase(identifier.toString(), this);
			demo.start();
			
			ArrayList sysinfo = new ArrayList();
			sysinfo.add(System.getProperty("java.version"));
			sysinfo.add(System.getProperty("java.vendor"));
			sysinfo.add(System.getProperty("os.name"));
			sysinfo.add(System.getProperty("os.arch"));
			sysinfo.add(System.getProperty("os.version"));
			printMessage(sysinfo);
			
			while (true) {
				answer = readMessage();
				assert(answer.size() > 1);
				assert(answer.get(0) instanceof Symbol);
				Symbol key = (Symbol)answer.get(0);
				System.out.println(key.toString() + " : " + answer);
				if (key.isEqual("project")) {
					assert(answer.size() == 2);
					assert(answer.get(1) instanceof String); //method name
					demo.project_chooser.addItem((String)answer.get(1));
					printMessage(result);
					continue;
				}
				if (key.isEqual("source")) {
					assert(answer.size() == 3);
					String name = null;
					assert(answer.get(1) instanceof String); //method name
					name = (String)answer.get(1);
					assert(answer.get(2) instanceof String); //source code
					if (! demo.string_source_map.containsKey(name)) {
						demo.string_source_map.put(name, (String)answer.get(2));
						demo.graph_chooser.addItem(new ListElement(name));
					}
					printMessage(result);
					continue;
				}
				assert(answer.get(1) instanceof String); //method name!
				String dfm_id = (String)answer.get(1);
				if (dfm_id.startsWith("top-level-initializer")) {
					printMessage(result);
					continue;
				}
				IncrementalHierarchicLayout gr = null;
				if (! graphs.containsKey(dfm_id)) {
					gr = new IncrementalHierarchicLayout(demo, dfm_id);
					graphs.put(dfm_id, gr);
					if (! demo.string_source_map.containsKey(dfm_id)) {
						demo.string_source_map.put(dfm_id, "no source");
						demo.graph_chooser.addItem(new ListElement(dfm_id));
					}
				}
				gr = graphs.get(dfm_id);
				if (gr.graphinprocessofbeingfinished) {
					if (dfm_id.equalsIgnoreCase("top-level-initializer")) {
						while (true) {
							if (gr.graphfinished) {
								gr.graphfinished = false;
								gr.graphinprocessofbeingfinished = false;
								break;
							}
							try {
								Thread.sleep(100);
							} catch (InterruptedException e) { }
						}
					} else {
						if (key.isEqual("beginning") && answer.get(2) instanceof ArrayList &&
								((ArrayList)answer.get(2)).size() == 1 && ((ArrayList)answer.get(2)).get(0) instanceof String &&
								((String)((ArrayList)answer.get(2)).get(0)).equalsIgnoreCase("initial DFM models")) { 
							printMessage(result);
							continue;
						} else {
							System.err.println("graph is already finished, go away");
							//printMessage(result);
							continue;
						}
					}
				}
				demo.activate(gr);
				Commands.processCommand(gr, answer, demo);
				if (! demo.wait.isSelected())
					printMessage(result);
			}
		} catch (IOException e) {
			//finish graphs, mark inactive
			for (IncrementalHierarchicLayout i : graphs.values())
				i.graphfinished = true;
			active = false;
			e.printStackTrace();
		}
	}
	
	private int compute_s_expression_size (Symbol message) {
		return message.toString().length();
	}
	private void write_s_expression (Symbol message) {
		writer.write(message.toString());
	}
	
	private int compute_s_expression_size (String message) {
		String m = message.replaceAll("\"", Matcher.quoteReplacement("\\\""));
		return 2 + m.length();
	}
	private void write_s_expression (String message) {
		writer.write((int)'"');
		writer.write(message.replaceAll("\"", Matcher.quoteReplacement("\\\"")));
		writer.write((int)'"');
	}
	
	private int compute_s_expression_size (Integer message) {
		return (Integer.toString(message)).length();
	}
	private void write_s_expression (Integer message) {
		writer.write(Integer.toString(message));
	}
	
	private int compute_s_expression_size (ArrayList message) {
		int size = 1; //'(' + ')'
		for (Object s : message) {
			if (s instanceof String)
				size += compute_s_expression_size((String)s);
			else if (s instanceof Symbol)
				size += compute_s_expression_size((Symbol)s);
			else if (s instanceof Integer)
				size += compute_s_expression_size((Integer)s);
			else if (s instanceof ArrayList)
				size += compute_s_expression_size((ArrayList)s);
			size++; //' '
		}
		return size;
	}
	private void write_s_expression (ArrayList message) {
		int i = message.size();
		writer.write((int)'(');
		for (Object s : message) {
			i--;
			if (s instanceof String)
				write_s_expression((String)s);
			else if (s instanceof Symbol)
				write_s_expression((Symbol)s);
			else if (s instanceof Integer)
				write_s_expression((Integer)s);
			else if (s instanceof ArrayList)
				write_s_expression((ArrayList)s);
			if (i > 0)
				writer.write((int)' ');
		}
		writer.write((int)')');
	}
	
	public void printMessage (ArrayList message) {
		int size = compute_s_expression_size(message);
		String sizeb = Integer.toHexString(size);
		for (int i = sizeb.length(); i < 6; i++)
			writer.write((int)'0');
		writer.write(sizeb);
		write_s_expression(message);
		writer.flush();
	}
	
	private enum ParseState { Number, Symbol, String, Nested };
	
	private boolean isWhitespace (char next) {
		if (next == ' ' | next == '\t' | next == '\n' | next == '\r')
			return true;
		return false;
	}
	
	private int usedTokens = 0;
	private int level = 0;
	private ArrayList read_s_expression (int message_length) throws IOException {
		level++;
		//System.out.println("called with level " + level + " message length " + message_length);
		ArrayList res = new ArrayList();
		ParseState state = ParseState.Nested;
		String result = "";
		boolean first = true;
		for (int i = 0; i < message_length; i++) {
			char next = (char)reader.read();
			//System.out.println("result:" + result + " i:" + i + " state:" + state + " next:" + next);
			switch (state) {
			case Number:
				if (isWhitespace(next)) {
					res.add(Integer.parseInt(result));
					result = "";
					state = ParseState.Nested;
				} else if (Character.isDigit(next)) {
					result += Character.toString(next);
				} else if (next == ')') {
					res.add(Integer.parseInt(result));
					usedTokens = i + 1;
					level--;
					return res;
				}
				break;
			case Symbol:
				if (isWhitespace(next)) {
					res.add(new Symbol(result));
					result = "";
					state = ParseState.Nested;
				} else if (next == ')') {
					res.add(new Symbol(result));
					usedTokens = i + 1;
					level--;
					return res;
				} else
					result += Character.toString(next);
				break;
			case String:
				if (next == '"') {
					res.add(result);
					result = "";
					state = ParseState.Nested;
				} else {
					if (next == '\\') {
						char nextt = (char)reader.read();
						i++;
						if (nextt == 't') result += "\t";
						else if (nextt == 'r') result += "\r";
						else if (nextt == 'n') result += "\n";
						else result += Character.toString(nextt);
					} else
						result += Character.toString(next);
				}
				break;
			case Nested:
				if (isWhitespace(next)) {
					//do nothing
					break;
				} else if (next == '(') {
					if (first && level == 1) break;
					res.add(read_s_expression(message_length - i - 1));
					i += usedTokens;
				} else if (next == '"') {
					state = ParseState.String;
				} else if (Character.isDigit(next)) {
					state = ParseState.Number;
					result += Character.toString(next);
				} else if (next == ')') {
					usedTokens = i + 1;
					level--;
					return res;
				} else {
					state = ParseState.Symbol;
					result += Character.toString(next);
				}
				break;
			}
			first = false;
		}
		level--;
		//System.out.println("leaving outer level");
		return res;
	}

}
