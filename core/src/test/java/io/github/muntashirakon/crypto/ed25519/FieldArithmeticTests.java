package io.github.muntashirakon.crypto.ed25519;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;

/** Synthetic test-only integer oracle; production arithmetic never uses BigInteger. */
public final class FieldArithmeticTests {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final Ed25519Field F = new Ed25519Field(256,
            Utils.hexToBytes("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"));
    private static int checked;

    public static void main(String[] args) {
        run();
        System.out.println("FIELD ARITHMETIC: " + checked + " independent checks passed");
    }

    public static void run() {
        checked = 0;
        fixedVectors();
        // Every schoolbook coefficient, both signs and every wrap at 2^255.
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                int[] a = new int[10], b = new int[10];
                a[i] = bound(i); b[j] = -bound(j);
                checkPair(a, b);
            }
        }
        // All sign combinations at the documented 1.65 * 2^(26,25) limits.
        for (int mask = 0; mask < 1024; mask++) {
            int[] a = new int[10], b = new int[10];
            for (int i = 0; i < 10; i++) {
                a[i] = ((mask >>> i) & 1) == 0 ? bound(i) : -bound(i);
                b[i] = a[i] < 0 ? bound(i) - 1 : 1 - bound(i);
            }
            checkPair(a, b);
        }
        Random random = new Random(0x25519dAL);
        for (int n = 0; n < 4096; n++) {
            int[] a = new int[10], b = new int[10];
            for (int i = 0; i < 10; i++) {
                a[i] = random.nextInt(bound(i) * 2 + 1) - bound(i);
                b[i] = random.nextInt(bound(i) * 2 + 1) - bound(i);
            }
            checkPair(a, b);
            if (n < 64) {
                Ed25519FieldElement value = new Ed25519FieldElement(F, a);
                assertValue(value.invert(), integer(a).mod(P).modInverse(P), "inverse");
                assertValue(value.pow22523(), integer(a).mod(P).modPow(
                        BigInteger.ONE.shiftLeft(252).subtract(BigInteger.valueOf(3)), P), "pow22523");
            }
        }
    }

    private static void fixedVectors() {
        vector(new int[10], new int[10],
                "0000000000000000000000000000000000000000000000000000000000000000",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "0000000000000000000000000000000000000000000000000000000000000000");
        vector(new int[]{1,0,0,0,0,0,0,0,0,0}, new int[]{-1,0,0,0,0,0,0,0,0,0},
                "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
                "0100000000000000000000000000000000000000000000000000000000000000",
                "0200000000000000000000000000000000000000000000000000000000000000");
        vector(new int[]{-1,0,0,0,0,0,0,0,0,0}, new int[]{-1,0,0,0,0,0,0,0,0,0},
                "0100000000000000000000000000000000000000000000000000000000000000",
                "0100000000000000000000000000000000000000000000000000000000000000",
                "0200000000000000000000000000000000000000000000000000000000000000");
        int[] limits = new int[10];
        for (int i = 0; i < 10; i++) limits[i] = bound(i);
        vector(limits, limits,
                "5e7f949373bd568214323285f725293c3ef6c8c9713d5717ae1ed3cce4e5a378",
                "5e7f949373bd568214323285f725293c3ef6c8c9713d5717ae1ed3cce4e5a378",
                "cffe2827e77aad042964640aef4b52787cec9193e37aae2e5c3da699c9cb4771");
        int[] mixed = limits.clone(), negative = limits.clone();
        for (int i = 0; i < 10; i++) { if ((i & 1) != 0) mixed[i] = -mixed[i]; negative[i] = -negative[i]; }
        vector(mixed, negative,
                "7cee51bedffaa207d7f3ea28d8b5f5a8bfd6c37499993d3133d6295cef633d32",
                "925161a923b3b7e21e611714fa9f9ab98b78b451f151e382c21c6db8460b0a5f",
                "37a3c25247666fc53dc22e28f43f357317f168a3e2a3c6058539da708d16143e");
        vector(new int[]{0,1,0,0,0,0,0,0,0,0}, new int[]{0,0,0,0,0,0,0,0,0,1},
                "2600000000000000000000000000000000000000000000000000000000000000",
                "0000000000001000000000000000000000000000000000000000000000000000",
                "0000000000002000000000000000000000000000000000000000000000000000");
        vector(new int[]{1342177,-16777215,33554431,-1234567,7654321,-1,19,-17000000,50000000,-33333333},
                new int[]{11111111,2222222,-33333333,4444444,-55555555,6666666,-77777777,8888888,99999999,-11111111},
                "90636b123b37c49b7a39de1fa7020e7402025cc2331eb61998b89b149c772417",
                "ce4174bbe954435f3dcbb197510ecace39e803ed814dce5690f6fd85349bf632",
                "9c83e876d3a986be7a96632fa31c949d73d007da039b9cad20edfb0b6936ed65");
    }

    private static void vector(int[] a, int[] b, String product, String square, String doubled) {
        // Expected bytes were computed independently with Python integer modulo p.
        Ed25519FieldElement x = new Ed25519FieldElement(F, a), y = new Ed25519FieldElement(F, b);
        assertBytes(x.multiply(y), product); assertBytes(x.square(), square); assertBytes(x.squareAndDouble(), doubled);
        checkPair(a, b);
    }

    private static void assertBytes(FieldElement actual, String expected) {
        if (!Arrays.equals(actual.toByteArray(), Utils.hexToBytes(expected))) throw new AssertionError("fixed arithmetic vector");
        checked++;
    }

    private static void checkPair(int[] a, int[] b) {
        int[] beforeA = a.clone(), beforeB = b.clone();
        BigInteger ai = integer(a), bi = integer(b);
        Ed25519FieldElement x = new Ed25519FieldElement(F, a), y = new Ed25519FieldElement(F, b);
        assertValue(x.multiply(y), ai.multiply(bi), "multiply");
        assertValue(y.multiply(x), bi.multiply(ai), "multiply reverse");
        assertValue(x.square(), ai.multiply(ai), "square");
        assertValue(x.squareAndDouble(), ai.multiply(ai).shiftLeft(1), "squareAndDouble");
        if (!Arrays.equals(a, beforeA) || !Arrays.equals(b, beforeB)) throw new AssertionError("input changed");
    }

    private static void assertValue(FieldElement actual, BigInteger expected, String operation) {
        BigInteger reduced = expected.mod(P);
        int[] limbs = ((Ed25519FieldElement) actual).t;
        if (!integer(limbs).mod(P).equals(reduced)) throw new AssertionError(operation + " limbs");
        byte[] encoded = actual.toByteArray(), bigEndian = new byte[32];
        for (int i = 0; i < 32; i++) bigEndian[i] = encoded[31 - i];
        if (!new BigInteger(1, bigEndian).equals(reduced)) throw new AssertionError(operation + " encoding");
        for (int i = 0; i < 10; i++) {
            long limit = 101L * (1L << ((i & 1) == 0 ? 25 : 24)) / 100;
            if (Math.abs((long) limbs[i]) > limit) throw new AssertionError(operation + " limb bound " + i);
        }
        checked++;
    }

    private static BigInteger integer(int[] limbs) {
        BigInteger result = BigInteger.ZERO;
        int offset = 0;
        for (int i = 0; i < 10; i++) {
            result = result.add(BigInteger.valueOf(limbs[i]).shiftLeft(offset));
            offset += (i & 1) == 0 ? 26 : 25;
        }
        return result;
    }

    private static int bound(int index) {
        return (int) (165L * (1L << ((index & 1) == 0 ? 26 : 25)) / 100);
    }
}
