import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

public class SimpleHuffProcessor implements IHuffProcessor {

	private static final int NO_VALUE = -1;

	private IHuffViewer myViewer;

	private HashMap<Integer, String> encoder;
	private int headerFormat;
	private String header;
	private int uncompressedSize;
	private int compressedSize;

	public SimpleHuffProcessor() {
		header = "";
		encoder = new HashMap<>();
	}

	/**
	 * Preprocess data so that compression is possible ---
	 * count characters/create tree/store state so that
	 * a subsequent call to compress will work. The InputStream
	 * is <em>not</em> a BitInputStream, so wrap it int one as needed.
	 * @param in is the stream which could be subsequently compressed
	 * @param headerFormat a constant from IHuffProcessor that determines what kind of
	 * header to use, standard count format, standard tree format, or
	 * possibly some format added in the future.
	 * @return number of bits saved by compression or some other measure
	 * Note, to determine the number of
	 * bits saved, the number of bits written includes
	 * ALL bits that will be written including the
	 * magic number, the header format number, the header to
	 * reproduce the tree, AND the actual data.
	 * @throws IOException if an error occurs while reading from the input file.
	 */
	public int preprocessCompress(InputStream in, int headerFormat) throws IOException {
		this.headerFormat = headerFormat;
		header = "";
		encoder = new HashMap<>();
		uncompressedSize = 0;
		compressedSize = 0;

		// create an int array to store the frequencies of the characters
		// and then create a tree to store the result
		int[] frequencies = getCharacterFrequencies(new BitInputStream(in));
		TreeNode huffmanTree = getTree(frequencies);

		// creates the map for the compressed data
		getCompressedMappings(huffmanTree);

		// generate the header for the tree
		generateHeader(frequencies, huffmanTree);

		// return how many bits we saved 
		return sizeDifference(frequencies, huffmanTree);
	}

	// method that counts the character's frequencies
	private int[] getCharacterFrequencies(BitInputStream uncompressedFile) throws IOException {
		int[] frequencies = new int[ALPH_SIZE];

		int character;

		// while the character is not -1, find the index which means the character, and add 1 to the frequency
		while ((character = uncompressedFile.read()) != NO_VALUE) {
			frequencies[character]++;

			// count the bits
			uncompressedSize += BITS_PER_WORD;
		}

		return frequencies;
	}

	// method that creates the tree
	private TreeNode getTree(int[] frequencies) {
		ArrayList<TreeNode> container = new ArrayList<>();

		// loop through the frequencies int array
		for (int character = 0; character < frequencies.length; character++)

			// if the character exists, enqueue it
			if (frequencies[character] != 0)
				enqueue(new TreeNode(character, frequencies[character]), container);

		// enqueue the PSUDO_EOF to the tree
		enqueue(new TreeNode(PSEUDO_EOF, 1), container);

		// remove the first two node and enqueue it to the tree
		while (container.size() > 1)
			enqueue(new TreeNode(container.remove(0), NO_VALUE, container.remove(0)), container);

		return container.get(0);
	}

	// method that add the treenodes to the arraylist
	private void enqueue(TreeNode value, ArrayList<TreeNode> container) {
		Iterator<TreeNode> it = container.iterator();
		int index = 0;

		// compare the frequencies between each value and place it
		while (it.hasNext() && value.compareTo(it.next()) >= 0)
			index++;

		// if the container is empty, add the value directly
		container.add(index, value);
	}

	// method that put the tree into a map
	private void getCompressedMappings(TreeNode huffmanTree) {		
		getCompressedMappings(huffmanTree, "");
	}

	// the recursion
	private void getCompressedMappings(TreeNode node, String sequence) {

		// base case, if the node contains an actual value
		if (node.getValue() != NO_VALUE)

			// put the node and the sequence to the map
			encoder.put(node.getValue(), sequence);

		// else, add 0 when it goes to the left, add 1 when it goes to the right
		else {
			getCompressedMappings(node.getLeft(), (sequence + "0"));
			getCompressedMappings(node.getRight(), (sequence + "1"));
		}
	}

	// method that generates the header
	private void generateHeader(int[] frequencies, TreeNode huffmanTree) {

		// if the format is STORE_TREE, generate the header with the length of the header
		if (headerFormat == STORE_TREE) {
			getHeader(huffmanTree);
			header = formatBinary(BITS_PER_INT, header.length()) + header;
		}

		// else add the frequency by bits into the header
		else
			for (int frequency : frequencies)
				header += formatBinary(BITS_PER_INT, frequency);
	}

	// method that gets the header
	private void getHeader(TreeNode node) {

		// base case, if the node contains the actual value
		// add 1 and the binary type of the value
		if (node.getValue() != NO_VALUE)
			header += '1' + formatBinary((BITS_PER_WORD + 1), node.getValue());

		// else, add 0 to the header, and do the recursion
		else {
			header += '0';
			getHeader(node.getLeft());
			getHeader(node.getRight());
		}
	}

	// method that change the actual value into its binary type
	private String formatBinary(int size, int number) {
		return String.format("%" + size + "s", Integer.toBinaryString(number)).replace(' ', '0');
	}

	// method that finds how many bits we saved
	private int sizeDifference(int[] frequencies, TreeNode huffmanTree) {

		// the compressed size is the header format, the magic number, the PSEUDO_EOF, and the header's length
		compressedSize = (2 * BITS_PER_INT) + encoder.get(PSEUDO_EOF).length() + header.length();

		// loop through the frequencies, and add the bits for each character
		for (int character = 0; character < ALPH_SIZE; character++)
			// if the character is presented, add the characters length and times the frequency of the character
			if (encoder.containsKey(character))
				compressedSize += encoder.get(character).length() * frequencies[character];

		//The difference between the previous and new size is returned.
		return uncompressedSize - compressedSize;
	}

	/**
	 * Compresses input to output, where the same InputStream has
	 * previously been pre-processed via <code>preprocessCompress</code>
	 * storing state used by this call.
	 * <br> pre: <code>preprocessCompress</code> must be called before this method
	 * @param in is the stream being compressed (NOT a BitInputStream)
	 * @param out is bound to a file/stream to which bits are written
	 * for the compressed file (not a BitOutputStream)
	 * @param force if this is true create the output file even if it is larger than the input file.
	 * If this is false do not create the output file if it is larger than the input file.
	 * @return the number of bits written.
	 * @throws IOException if an error occurs while reading from the input file or
	 * writing to the output file.
	 */
	public int compress(InputStream in, OutputStream out, boolean force) throws IOException {
		if (compressedSize > uncompressedSize && !force)
			return 0;

		//Wrapps the in and out in wrapper Classes.
		BitInputStream reader = new BitInputStream(in);
		BitOutputStream writer = new BitOutputStream(out);

		//Writes the magic number, header format number, and the header bits.
		writer.writeBits(BITS_PER_INT, MAGIC_NUMBER);
		writer.writeBits(BITS_PER_INT, headerFormat);
		writeSequence(header, writer);

		//Writes the encoded bit version for each character.
		int character;
		while ((character = reader.read()) != NO_VALUE)
			writeSequence(encoder.get(character), writer);

		//Writes the ending PSEUDO_EOF character, writing an extra byte of data to prevent bits from being excluded.
		writer.writeBits(encoder.get(PSEUDO_EOF).length(), Integer.valueOf(encoder.get(PSEUDO_EOF), 2));
		if (compressedSize % BITS_PER_WORD > 0)
			writer.writeBits(BITS_PER_WORD, 0);

		//The size of the compressed file is returned.
		return compressedSize;
	}

	//Takes a string sequence and passes the values to a BitOutputStream.
	private void writeSequence(String sequence, BitOutputStream writer) {
		for (char bit : sequence.toCharArray())
			writer.writeBits(1, (bit == '0' ? 0 : 1));
	}

	/**
	 * Uncompress a previously compressed stream in, writing the
	 * uncompressed bits/data to out.
	 * @param in is the previously compressed data (not a BitInputStream)
	 * @param out is the uncompressed file/stream
	 * @return the number of bits written to the uncompressed file/stream
	 * @throws IOException if an error occurs while reading from the input file or
	 * writing to the output file.
	 */
	public int uncompress(InputStream in, OutputStream out) throws IOException {
		BitInputStream reader = new BitInputStream(in);
		BitOutputStream writer = new BitOutputStream(out);

		//If the header doesn't start with a magic number, an error is thrown.
		if (reader.readBits(BITS_PER_INT) != MAGIC_NUMBER)
			throwError("Error reading file. Doesn't start with MAGIC_NUMBER.", reader);

		//If a valid header isn't present, an error is thrown.
		int headerFormat = reader.readBits(BITS_PER_INT);
		if (headerFormat != STORE_TREE && headerFormat != STORE_COUNTS && headerFormat != STORE_CUSTOM)
			throwError("Error reading file. Can't determine header format", reader);

		//The decoding tree is generated from the header data.
		TreeNode decoder = getDecoder(headerFormat, reader);

		//The list of characters from the compressed bits is found.
		ArrayList<Integer> compressedBits = getContent(decoder, headerFormat, reader);

		//The fill 8-bit form of these characters is added to the new file.
		for (int character : compressedBits)
			writer.writeBits(BITS_PER_WORD, character);
		writer.close();

		//The total number of bits written to the file is returned.
		return compressedBits.size() * BITS_PER_WORD;
	}

	//Gets the tree from the header bit data.
	private TreeNode getDecoder(int headerFormat, BitInputStream reader) throws IOException {
		if (headerFormat == STORE_TREE) {
			//The number of bits required to make the header is read from the input file.
			int size = reader.readBits(BITS_PER_INT);
			if (size == NO_VALUE)
				throw new IOException("Error reading file. Can't determine size of header.");

			//The entire tree bit-header is found.
			String header = "";
			for (int count = 1; count <= size; count++) {
				header += reader.readBits(1);
			}

			//If a -1 is present, an error is thrown. This is because not enough data was present.
			if (header.contains("-1"))
				throwError("Error reading file. Header data missing.", reader);

			//The tree is made from the bit sequences
			return unwrapTree(header, new int[] {0});
		} 

		//If the header stores counts, the tree can be directly created using the getTree method.
		else {
			int[] frequencies = new int[ALPH_SIZE];

			for (int character = 0; character < ALPH_SIZE; character++)
				if ((frequencies[character] = reader.readBits(BITS_PER_INT)) == NO_VALUE)
					throwError("Error reading file. Header data missing.", reader);

			return getTree(frequencies);
		}
	}

	//Traverses through the bit tree to place nodes in the correct position.
	private TreeNode unwrapTree(String header, int[] index) {
		index[0]++;

		//1 indicates a leaf node.
		if (header.charAt(index[0] - 1) == '1') {
			return new TreeNode(Integer.parseInt(header.substring(index[0], index[0] += 9), 2), NO_VALUE);
		}

		//If the node isn't a leaf node, it has two child nodes.
		TreeNode node = new TreeNode(NO_VALUE, NO_VALUE);
		node.setLeft(unwrapTree(header, index));
		node.setRight(unwrapTree(header, index));

		return node;
	}

	//Returns the total characters encoded within the file.
	private ArrayList<Integer> getContent(TreeNode decoder, int headerFormat, BitInputStream reader) throws IOException {
		ArrayList<Integer> compressedBits = new ArrayList<>();
		TreeNode node = decoder;

		//Reads all the remaining bits.
		int bit = reader.readBits(1);
		while (bit != NO_VALUE) {
			//Traverses left or right based on the binary value.
			if (bit == 0)
				node = node.getLeft();
			else
				node = node.getRight();

			//If a node is reached, that character is added to the total character list. Once PSEUDO_EOF is
			//reached, the code is stopped.
			if(node.getValue() == PSEUDO_EOF)
				return compressedBits;
			else if (node.getValue() != NO_VALUE){
				compressedBits.add(node.getValue());
				node = decoder;
			}
			bit = reader.readBits(1);
		}

		//If PSEUDO_OF is never reached, an error is thrown.
		throw new IOException("The PSEUDO_EOF character isn't present.");
	}

	//Helper method to throw error
	private void throwError(String exception, BitInputStream reader) throws IOException {
		reader.close();
		throw new IOException(exception);
	}


	//GIVEN METHODS

	public void setViewer(IHuffViewer viewer) {
		myViewer = viewer;
	}



	private void showString(String s){
		if(myViewer != null)
			myViewer.update(s);
	}
}
