import java.util.PriorityQueue;

/**
 * Although this class has a history of several years,
 * it is starting from a blank-slate, new and clean implementation
 * as of Fall 2018.
 * <P>
 * Changes include relying solely on a tree for header information
 * and including debug and bits read/written information
 * 
 * @author Owen Astrachan
 *
 * Revise
 */

public class HuffProcessor {


	private HuffNode readTree(BitInputStream in) {

        int bit = in.readBits(1);
        if (bit == -1) {
            throw new HuffException("Error reading tree: unexpected end of file.");
        }

        // Leaf node
        if (bit == 1) {
            int value = in.readBits(BITS_PER_WORD);
            return new HuffNode(value, 0, null, null);
        }

        // Internal node
        HuffNode left = readTree(in);
        HuffNode right = readTree(in);
        return new HuffNode(0, 0, left, right);
    }

	private class HuffNode implements Comparable<HuffNode> {
		HuffNode left;
		HuffNode right;
		int value;
		int weight;

		public HuffNode(int val, int count) {
			value = val;
			weight = count;
		}
		public HuffNode(int val, int count, HuffNode ltree, HuffNode rtree) {
			value = val;
			weight = count;
			left = ltree;
			right = rtree;
		}

		public int compareTo(HuffNode o) {
			return weight - o.weight;
		}
	}

	public static final int BITS_PER_WORD = 8;
	public static final int BITS_PER_INT = 32;
	public static final int ALPH_SIZE = (1 << BITS_PER_WORD); 
	public static final int PSEUDO_EOF = ALPH_SIZE;
	public static final int HUFF_NUMBER = 0xface8200;
	public static final int HUFF_TREE  = HUFF_NUMBER | 1;

	private boolean myDebugging = false;
	
	public HuffProcessor() {
		this(false);
	}
	
	public HuffProcessor(boolean debug) {
		myDebugging = debug;
	}

	/**
	 * Compresses a file. Process must be reversible and loss-less.
	 *
	 * @param in
	 *            Buffered bit stream of the file to be compressed.
	 * @param out
	 *            Buffered bit stream writing to the output file.
	 */
	/* ----------------------
   Compression + helpers
   ---------------------- */

public void compress(BitInputStream in, BitOutputStream out) {
    // 1) Count frequencies
    int[] counts = new int[ALPH_SIZE + 1]; // include pseudo-eof
    for (int i = 0; i < counts.length; i++) counts[i] = 0;

    while (true) {
        int val = in.readBits(BITS_PER_WORD);
        if (val == -1) break;
        counts[val]++;
    }
    counts[PSEUDO_EOF] = 1; // ensure pseudo-eof present

    // 2) Build tree from counts
    HuffNode root = makeTreeFromCounts(counts);

    // 3) Create encodings map
    String[] encodings = new String[ALPH_SIZE + 1];
    for (int i = 0; i < encodings.length; i++) encodings[i] = null;
    makeEncodings(root, "", encodings);

    // 4) Write header: magic number and tree
    writeInt(HUFF_TREE, out);
    writeTree(root, out);

    // 5) Rewind input and write encoded data
    in.reset();
    while (true) {
        int val = in.readBits(BITS_PER_WORD);
        if (val == -1) break;
        String code = encodings[val];
        if (code == null) {
            throw new HuffException("No encoding for value: " + val);
        }
        for (char c : code.toCharArray()) {
            out.writeBits(1, c == '1' ? 1 : 0);
        }
    }
    // write PSEUDO_EOF
    String eofCode = encodings[PSEUDO_EOF];
    for (char c : eofCode.toCharArray()) {
        out.writeBits(1, c == '1' ? 1 : 0);
    }

    out.close();
}

/** Build Huff tree from counts using priority queue (greedy) */
private HuffNode makeTreeFromCounts(int[] counts) {
    PriorityQueue<HuffNode> pq = new PriorityQueue<HuffNode>();
    for (int i = 0; i < counts.length; i++) {
        if (counts[i] > 0) {
            // create leaf node: value i, weight counts[i]
            // if your HuffNode constructor takes (val, weight, left, right) use that
            HuffNode node;
            try {
                node = new HuffNode(i, counts[i], null, null);
            } catch (NoSuchMethodError e) {
                node = new HuffNode(i, counts[i]);
            }
            pq.add(node);
        }
    }

    // combine until one tree remains
    while (pq.size() > 1) {
        HuffNode left = pq.remove();
        HuffNode right = pq.remove();
        HuffNode parent;
        try {
            parent = new HuffNode(0, left.weight + right.weight, left, right);
        } catch (NoSuchMethodError e) {
            // if only two-arg constructor exists, create and then set children
            parent = new HuffNode(0, left.weight + right.weight);
            parent.left = left;
            parent.right = right;
        }
        pq.add(parent);
    }

    return pq.remove();
}

/** Fill encodings array (index = symbol) with bitstring encodings */
private void makeEncodings(HuffNode root, String path, String[] encodings) {
    if (root.left == null && root.right == null) {
        // leaf
        encodings[root.value] = path.length() > 0 ? path : "0"; // if single symbol edge case
        return;
    }
    if (root.left != null) {
        makeEncodings(root.left, path + '0', encodings);
    }
    if (root.right != null) {
        makeEncodings(root.right, path + '1', encodings);
    }
}

/** Write tree to output in preorder: leaf => 1 + 8-bit value; internal => 0 then left/right */
private void writeTree(HuffNode root, BitOutputStream out) {
    if (root.left == null && root.right == null) {
        // leaf
        out.writeBits(1, 1); // leaf marker
        out.writeBits(BITS_PER_WORD, root.value);
        return;
    }
    // internal node
    out.writeBits(1, 0);
    writeTree(root.left, out);
    writeTree(root.right, out);
}

/** Write a 32-bit int to output (big-endian), using BITS_PER_INT bits */
private void writeInt(int val, BitOutputStream out) {
    out.writeBits(BITS_PER_INT, val);
}


	/**
	 * Decompresses a file. Output file must be identical bit-by-bit to the
	 * original.
	 *
	 * @param in
	 *            Buffered bit stream of the file to be decompressed.
	 * @param out
	 *            Buffered bit stream writing to the output file.
	 */
public void decompress(BitInputStream in, BitOutputStream out) {
    // 1) read/check magic number/header
    int magic = in.readBits(BITS_PER_INT);
    if (magic != HUFF_TREE) {
        throw new HuffException("Invalid header / magic number: " + magic);
    }

    // 2) read the tree used for compression/decompression
    HuffNode root = readTree(in);   // <-- assumes this helper exists in the class
    if (root == null) {
        throw new HuffException("Missing Huffman tree in input");
    }

    // 3) read bits one at a time; traverse the tree until reaching leaves
    HuffNode current = root;
    while (true) {
        int bit = in.readBits(1);
        if (bit == -1) {
            // unexpected EOF before PSEUDO_EOF
            throw new HuffException("Unexpected end of input while decompressing");
        }
        // traverse
        if (bit == 0) {
            current = current.left;
        } else {
            current = current.right;
        }

        // when we hit a leaf node, output or stop if PSEUDO_EOF
        if (current.left == null && current.right == null) { // leaf
            if (current.value == PSEUDO_EOF) {
                // done decompressing
                break;
            } else {
                // write the decoded byte/word
                out.writeBits(BITS_PER_WORD, current.value);
                // restart at the root for next symbol
                current = root;
            }
        }
    }

    // 4) close output file before returning (important)
    out.close();
}

}