import java.util.PriorityQueue;

/**
 * Clean Huffman processor implementing compress and decompress.
 * Drop this file into your project (replace existing HuffProcessor.java).
 */
public class HuffProcessor {

    // inner node class
    private class HuffNode implements Comparable<HuffNode> {
        HuffNode left;
        HuffNode right;
        int value;
        int weight;

        // leaf constructor
        public HuffNode(int val, int count) {
            value = val;
            weight = count;
            left = null;
            right = null;
        }

        // internal / full constructor
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

    // constants (matching your project)
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

    /* ----------------------
       DECOMPRESS
       ---------------------- */
    /**
     * Decompresses a file. Output file must be identical bit-by-bit to the
     * original.
     *
     * Steps:
     * 1) read magic header
     * 2) read tree
     * 3) walk bits and decode using tree until PSEUDO_EOF
     * 4) close output
     */
    public void decompress(BitInputStream in, BitOutputStream out) {

        
        int magic = in.readBits(BITS_PER_INT);
        if (magic != HUFF_TREE) {
            throw new HuffException("Invalid header / magic number: " + magic);
        }

        
        HuffNode root = readTree(in);
        if (root == null) {
            throw new HuffException("Missing Huffman tree in input");
        }

        
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

    /**
     * Reconstructs the Huffman tree from the input header.
     *
     * Format assumed:
     * preorder traversal:
     *   leaf: 1 bit '1' followed by BITS_PER_WORD bits of value
     *   internal: 1 bit '0' then left subtree then right subtree
     */
    private HuffNode readTree(BitInputStream in) {
    int bit = in.readBits(1);
    if (bit == -1) {
        throw new HuffException("Error reading tree: unexpected end of file.");
    }

    if (bit == 1) {
        // Note: read BITS_PER_WORD+1 bits for the value (to include PSEUDO_EOF)
        int value = in.readBits(BITS_PER_WORD + 1);
        if (value == -1) {
            throw new HuffException("Error reading tree: unexpected end of file for leaf value.");
        }
        return new HuffNode(value, 0, null, null);
    } else {
        HuffNode left = readTree(in);
        HuffNode right = readTree(in);
        return new HuffNode(0, 0, left, right);
    }
}


    /* ----------------------
       COMPRESS
       ---------------------- */

    /**
     * Compress input -> output using Huffman coding.
     * Steps:
     * 1) count frequencies
     * 2) make tree
     * 3) create encodings
     * 4) write header (magic + tree)
     * 5) write encoded data (second pass), finishing with PSEUDO_EOF
     */
    public void compress(BitInputStream in, BitOutputStream out) {
        
        int[] counts = new int[ALPH_SIZE + 1]; // include pseudo-eof
        for (int i = 0; i < counts.length; i++) counts[i] = 0;

        
        while (true) {
            int val = in.readBits(BITS_PER_WORD);
            if (val == -1) break;
            counts[val]++;
        }
        counts[PSEUDO_EOF] = 1; // ensure pseudo-eof present

        
        HuffNode root = makeTreeFromCounts(counts);

        
        String[] encodings = new String[ALPH_SIZE + 1];
        for (int i = 0; i < encodings.length; i++) encodings[i] = null;
        makeEncodings(root, "", encodings);

        
        writeInt(HUFF_TREE, out);
        writeTree(root, out);

        
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
                HuffNode node = new HuffNode(i, counts[i], null, null);
                pq.add(node);
            }
        }

        
        while (pq.size() > 1) {
            HuffNode left = pq.remove();
            HuffNode right = pq.remove();
            HuffNode parent = new HuffNode(0, left.weight + right.weight, left, right);
            pq.add(parent);
        }

        
        if (pq.isEmpty()) {
            throw new HuffException("Cannot build tree from empty input");
        }
        return pq.remove();
    }

    /** Fill encodings array (index = symbol) with bitstring encodings */
    private void makeEncodings(HuffNode root, String path, String[] encodings) {
        if (root.left == null && root.right == null) {
            // leaf
            // if tree only has single symbol, ensure a code of length >=1
            encodings[root.value] = path.length() > 0 ? path : "0";
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
        // leaf: write marker bit 1, then the value using BITS_PER_WORD+1 bits
        out.writeBits(1, 1);
        out.writeBits(BITS_PER_WORD + 1, root.value);
        return;
    }
    // internal node: marker 0 then left, right
    out.writeBits(1, 0);
    writeTree(root.left, out);
    writeTree(root.right, out);
}


    /** Write a 32-bit int to output (uses BITS_PER_INT) */
    private void writeInt(int val, BitOutputStream out) {
        out.writeBits(BITS_PER_INT, val);
    }

}
