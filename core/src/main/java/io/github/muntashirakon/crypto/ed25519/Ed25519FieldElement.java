/*
 * Copyright (C) 2021 Muntashir Al-Islam
 *
 * Licensed according to the LICENSE file in the upstream repository (LGPL-3.0).
 * Local changes and licenses: third_party/spake2-java/UPSTREAM_MANIFEST.json,
 * licenses/LGPL-3.0 and licenses/CC0-1.0.
 *
 * The multiply method is restored from EdDSA-Java by str4d (CC0).
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java. See https://creativecommons.org/publicdomain/zero/1.0/.
 */

package io.github.muntashirakon.crypto.ed25519;

import java.util.Arrays;

/**
 * Class to represent a field element of the finite field $p = 2^{255} - 19$ elements.
 * <p>
 * An element $t$, entries $t[0] \dots t[9]$, represents the integer
 * $t[0]+2^{26} t[1]+2^{51} t[2]+2^{77} t[3]+2^{102} t[4]+\dots+2^{230} t[9]$.
 * Bounds on each $t[i]$ vary depending on context.
 * <p>
 * Reviewed/commented by Bloody Rookie (nemproject@gmx.de)
 */
public class Ed25519FieldElement extends FieldElement {
    /**
     * Variable is package private for encoding.
     */
    protected final int[] t;

    /**
     * Creates a field element.
     *
     * @param f The underlying field, must be the finite field with $p = 2^{255} - 19$ elements
     * @param t The $2^{25.5}$ bit representation of the field element.
     */
    public Ed25519FieldElement(Ed25519Field f, int[] t) {
        super(f);
        if (t.length != 10)
            throw new IllegalArgumentException("Invalid radix-2^51 representation");
        this.t = t;
    }

    private static final byte[] ZERO = new byte[32];

    /**
     * Gets a value indicating whether the field element is non-zero.
     *
     * @return 1 if it is non-zero, 0 otherwise.
     */
    public boolean isNonZero() {
        final byte[] s = toByteArray();
        return Utils.equal(s, ZERO) == 0;
    }

    /**
     * $h = f + g$
     * <p>
     * TODO-CR BR: $h$ is allocated via new, probably not a good idea. Do we need the copying into temp variables if we do that?
     * <p>
     * Preconditions:
     * </p><ul>
     * <li>$|f|$ bounded by $1.1*2^{25},1.1*2^{24},1.1*2^{25},1.1*2^{24},$ etc.
     * <li>$|g|$ bounded by $1.1*2^{25},1.1*2^{24},1.1*2^{25},1.1*2^{24},$ etc.
     * </ul><p>
     * Postconditions:
     * </p><ul>
     * <li>$|h|$ bounded by $1.1*2^{26},1.1*2^{25},1.1*2^{26},1.1*2^{25},$ etc.
     * </ul>
     *
     * @param val The field element to add.
     * @return The field element this + val.
     */
    public FieldElement add(FieldElement val) {
        int[] g = ((Ed25519FieldElement)val).t;
        int[] h = new int[10];
        for (int i = 0; i < 10; i++) {
            h[i] = t[i] + g[i];
        }
        return new Ed25519FieldElement(f, h);
    }

    /**
     * $h = f - g$
     * <p>
     * Can overlap $h$ with $f$ or $g$.
     * <p>
     * TODO-CR BR: See above.
     * <p>
     * Preconditions:
     * </p><ul>
     * <li>$|f|$ bounded by $1.1*2^{25},1.1*2^{24},1.1*2^{25},1.1*2^{24},$ etc.
     * <li>$|g|$ bounded by $1.1*2^{25},1.1*2^{24},1.1*2^{25},1.1*2^{24},$ etc.
     * </ul><p>
     * Postconditions:
     * </p><ul>
     * <li>$|h|$ bounded by $1.1*2^{26},1.1*2^{25},1.1*2^{26},1.1*2^{25},$ etc.
     * </ul>
     *
     * @param val The field element to subtract.
     * @return The field element this - val.
     **/
    public FieldElement subtract(FieldElement val) {
        int[] g = ((Ed25519FieldElement)val).t;
        int[] h = new int[10];
        for (int i = 0; i < 10; i++) {
            h[i] = t[i] - g[i];
        }
        return new Ed25519FieldElement(f, h);
    }

    /**
     * $h = -f$
     * <p>
     * TODO-CR BR: see above.
     * <p>
     * Preconditions:
     * </p><ul>
     * <li>$|f|$ bounded by $1.1*2^{25},1.1*2^{24},1.1*2^{25},1.1*2^{24},$ etc.
     * </ul><p>
     * Postconditions:
     * </p><ul>
     * <li>$|h|$ bounded by $1.1*2^{25},1.1*2^{24},1.1*2^{25},1.1*2^{24},$ etc.
     * </ul>
     *
     * @return The field element (-1) * this.
     */
    public FieldElement negate() {
        int[] h = new int[10];
        for (int i = 0; i < 10; i++) {
            h[i] = - t[i];
        }
        return new Ed25519FieldElement(f, h);
    }

    /**
     * $h = f * g$
     * <p>
     * Can overlap $h$ with $f$ or $g$.
     * <p>
     * Preconditions:
     * </p><ul>
     * <li>$|f|$ bounded by
     * $1.65*2^{26},1.65*2^{25},1.65*2^{26},1.65*2^{25},$ etc.
     * <li>$|g|$ bounded by
     * $1.65*2^{26},1.65*2^{25},1.65*2^{26},1.65*2^{25},$ etc.
     * </ul><p>
     * Postconditions:
     * </p><ul>
     * <li>$|h|$ bounded by
     * $1.01*2^{25},1.01*2^{24},1.01*2^{25},1.01*2^{24},$ etc.
     * </ul><p>
     * Notes on implementation strategy:
     * <p>
     * Using schoolbook multiplication. Karatsuba would save a little in some
     * cost models.
     * <p>
     * Products are accumulated directly into ten long limbs to keep the
     * Android 4.4 Dalvik JIT virtual-register working set small.
     * <p>
     * Reduction preserves the original signed carry chain, including the
     * multiplication by 19 when carrying out of the highest limb.
     * <p>
     * There are 12 carries below. 10 of them are 2-way parallelizable and
     * vectorizable. Can get away with 11 carries, but then data flow is much
     * deeper.
     * <p>
     * With tighter constraints on inputs can squeeze carries into int32.
     *
     * @param val The field element to multiply.
     * @return The (reasonably reduced) field element this * val.
     */
    public FieldElement multiply(FieldElement val) {
        int[] g = ((Ed25519FieldElement) val).t;
        long[] h = new long[10];
        h[0] = t[0] * (long) g[0] + t[1] * (long) g[9] * 38 + t[2] * (long) g[8] * 19 + t[3] * (long) g[7] * 38 + t[4] * (long) g[6] * 19 + t[5] * (long) g[5] * 38 + t[6] * (long) g[4] * 19 + t[7] * (long) g[3] * 38 + t[8] * (long) g[2] * 19 + t[9] * (long) g[1] * 38;
        h[1] = t[0] * (long) g[1] + t[1] * (long) g[0]    + t[2] * (long) g[9] * 19 + t[3] * (long) g[8] * 19 + t[4] * (long) g[7] * 19 + t[5] * (long) g[6] * 19 + t[6] * (long) g[5] * 19 + t[7] * (long) g[4] * 19 + t[8] * (long) g[3] * 19 + t[9] * (long) g[2] * 19;
        h[2] = t[0] * (long) g[2] + t[1] * (long) g[1] * 2  + t[2] * (long) g[0]    + t[3] * (long) g[9] * 38 + t[4] * (long) g[8] * 19 + t[5] * (long) g[7] * 38 + t[6] * (long) g[6] * 19 + t[7] * (long) g[5] * 38 + t[8] * (long) g[4] * 19 + t[9] * (long) g[3] * 38;
        h[3] = t[0] * (long) g[3] + t[1] * (long) g[2]    + t[2] * (long) g[1]    + t[3] * (long) g[0]    + t[4] * (long) g[9] * 19 + t[5] * (long) g[8] * 19 + t[6] * (long) g[7] * 19 + t[7] * (long) g[6] * 19 + t[8] * (long) g[5] * 19 + t[9] * (long) g[4] * 19;
        h[4] = t[0] * (long) g[4] + t[1] * (long) g[3] * 2  + t[2] * (long) g[2]    + t[3] * (long) g[1] * 2  + t[4] * (long) g[0]    + t[5] * (long) g[9] * 38 + t[6] * (long) g[8] * 19 + t[7] * (long) g[7] * 38 + t[8] * (long) g[6] * 19 + t[9] * (long) g[5] * 38;
        h[5] = t[0] * (long) g[5] + t[1] * (long) g[4]    + t[2] * (long) g[3]    + t[3] * (long) g[2]    + t[4] * (long) g[1]    + t[5] * (long) g[0]    + t[6] * (long) g[9] * 19 + t[7] * (long) g[8] * 19 + t[8] * (long) g[7] * 19 + t[9] * (long) g[6] * 19;
        h[6] = t[0] * (long) g[6] + t[1] * (long) g[5] * 2  + t[2] * (long) g[4]    + t[3] * (long) g[3] * 2  + t[4] * (long) g[2]    + t[5] * (long) g[1] * 2  + t[6] * (long) g[0]    + t[7] * (long) g[9] * 38 + t[8] * (long) g[8] * 19 + t[9] * (long) g[7] * 38;
        h[7] = t[0] * (long) g[7] + t[1] * (long) g[6]    + t[2] * (long) g[5]    + t[3] * (long) g[4]    + t[4] * (long) g[3]    + t[5] * (long) g[2]    + t[6] * (long) g[1]    + t[7] * (long) g[0]    + t[8] * (long) g[9] * 19 + t[9] * (long) g[8] * 19;
        h[8] = t[0] * (long) g[8] + t[1] * (long) g[7] * 2  + t[2] * (long) g[6]    + t[3] * (long) g[5] * 2  + t[4] * (long) g[4]    + t[5] * (long) g[3] * 2  + t[6] * (long) g[2]    + t[7] * (long) g[1] * 2  + t[8] * (long) g[0]    + t[9] * (long) g[9] * 38;
        h[9] = t[0] * (long) g[9] + t[1] * (long) g[8]    + t[2] * (long) g[7]    + t[3] * (long) g[6]    + t[4] * (long) g[5]    + t[5] * (long) g[4]    + t[6] * (long) g[3]    + t[7] * (long) g[2]    + t[8] * (long) g[1]    + t[9] * (long) g[0];
        return reduceProduct(h);
    }

    /** Returns the signed-radix square, with the same bounds as multiply. */
    public FieldElement square() {
        return reduceProduct(squareProduct());
    }

    /** Returns twice the square, reduced after doubling the full-width product. */
    public FieldElement squareAndDouble() {
        long[] h = squareProduct();
        for (int i = 0; i < 10; i++) h[i] += h[i];
        return reduceProduct(h);
    }

    /*
     * Keep each output limb in a fixed-index array rather than keeping all 100
     * multiplication (or 55 square) products live as Dalvik virtual registers.
     * Android 4.4 x86's JIT otherwise aborts with "exceeds size of memVRTable".
     *
     * These are exactly the signed ref10/EdDSA-Java schoolbook coefficients:
     * limb weights alternate 26 and 25 bits, and 2^255 = 19 (mod p). All array
     * indices and loop counts are public constants. No branch depends on a
     * field element. Promote before multiplying: each term and sum fits long
     * for the documented input bounds, including squareAndDouble's factor 2.
     */
    private long[] squareProduct() {
        long[] h = new long[10];
        h[0] = t[0] * (long) t[0]   + t[1] * (long) t[9] * 76 + t[2] * (long) t[8] * 38 + t[3] * (long) t[7] * 76 + t[4] * (long) t[6] * 38 + t[5] * (long) t[5] * 38;
        h[1] = t[0] * (long) t[1] * 2 + t[2] * (long) t[9] * 38 + t[3] * (long) t[8] * 38 + t[4] * (long) t[7] * 38 + t[5] * (long) t[6] * 38;
        h[2] = t[0] * (long) t[2] * 2 + t[1] * (long) t[1] * 2  + t[3] * (long) t[9] * 76 + t[4] * (long) t[8] * 38 + t[5] * (long) t[7] * 76 + t[6] * (long) t[6] * 19;
        h[3] = t[0] * (long) t[3] * 2 + t[1] * (long) t[2] * 2  + t[4] * (long) t[9] * 38 + t[5] * (long) t[8] * 38 + t[6] * (long) t[7] * 38;
        h[4] = t[0] * (long) t[4] * 2 + t[1] * (long) t[3] * 4  + t[2] * (long) t[2]    + t[5] * (long) t[9] * 76 + t[6] * (long) t[8] * 38 + t[7] * (long) t[7] * 38;
        h[5] = t[0] * (long) t[5] * 2 + t[1] * (long) t[4] * 2  + t[2] * (long) t[3] * 2  + t[6] * (long) t[9] * 38 + t[7] * (long) t[8] * 38;
        h[6] = t[0] * (long) t[6] * 2 + t[1] * (long) t[5] * 4  + t[2] * (long) t[4] * 2  + t[3] * (long) t[3] * 2  + t[7] * (long) t[9] * 76 + t[8] * (long) t[8] * 19;
        h[7] = t[0] * (long) t[7] * 2 + t[1] * (long) t[6] * 2  + t[2] * (long) t[5] * 2  + t[3] * (long) t[4] * 2  + t[8] * (long) t[9] * 38;
        h[8] = t[0] * (long) t[8] * 2 + t[1] * (long) t[7] * 4  + t[2] * (long) t[6] * 2  + t[3] * (long) t[5] * 4  + t[4] * (long) t[4]    + t[9] * (long) t[9] * 38;
        h[9] = t[0] * (long) t[9] * 2 + t[1] * (long) t[8] * 2  + t[2] * (long) t[7] * 2  + t[3] * (long) t[6] * 2  + t[4] * (long) t[5] * 2;
        return h;
    }

    /** The original twelve signed carries, in their original order. */
    private FieldElement reduceProduct(long[] h) {
        carryProduct(h, 0, 26);
        carryProduct(h, 4, 26);
        carryProduct(h, 1, 25);
        carryProduct(h, 5, 25);
        carryProduct(h, 2, 26);
        carryProduct(h, 6, 26);
        carryProduct(h, 3, 25);
        carryProduct(h, 7, 25);
        carryProduct(h, 4, 26);
        carryProduct(h, 8, 26);
        long carry = (h[9] + (1L << 24)) >> 25;
        h[0] += carry * 19;
        h[9] -= carry << 25;
        carryProduct(h, 0, 26);
        int[] limbs = new int[10];
        for (int i = 0; i < 10; i++) limbs[i] = (int) h[i];
        Arrays.fill(h, 0L);
        return new Ed25519FieldElement(f, limbs);
    }

    private static void carryProduct(long[] h, int index, int bits) {
        long carry = (h[index] + (1L << (bits - 1))) >> bits;
        h[index + 1] += carry;
        h[index] -= carry << bits;
    }

    /**
     * Invert this field element.
     * <p>
     * The inverse is found via Fermat's little theorem:<br>
     * $a^p \cong a \mod p$ and therefore $a^{(p-2)} \cong a^{-1} \mod p$
     *
     * @return The inverse of this field element.
     */
    public FieldElement invert() {
        FieldElement t0, t1, t2, t3;

        // 2 == 2 * 1
        t0 = square();

        // 4 == 2 * 2
        t1 = t0.square();

        // 8 == 2 * 4
        t1 = t1.square();

        // 9 == 8 + 1
        t1 = multiply(t1);

        // 11 == 9 + 2
        t0 = t0.multiply(t1);

        // 22 == 2 * 11
        t2 = t0.square();

        // 31 == 22 + 9
        t1 = t1.multiply(t2);

        // 2^6 - 2^1
        t2 = t1.square();

        // 2^10 - 2^5
        for (int i = 1; i < 5; ++i) {
            t2 = t2.square();
        }

        // 2^10 - 2^0
        t1 = t2.multiply(t1);

        // 2^11 - 2^1
        t2 = t1.square();

        // 2^20 - 2^10
        for (int i = 1; i < 10; ++i) {
            t2 = t2.square();
        }

        // 2^20 - 2^0
        t2 = t2.multiply(t1);

        // 2^21 - 2^1
        t3 = t2.square();

        // 2^40 - 2^20
        for (int i = 1; i < 20; ++i) {
            t3 = t3.square();
        }

        // 2^40 - 2^0
        t2 = t3.multiply(t2);

        // 2^41 - 2^1
        t2 = t2.square();

        // 2^50 - 2^10
        for (int i = 1; i < 10; ++i) {
            t2 = t2.square();
        }

        // 2^50 - 2^0
        t1 = t2.multiply(t1);

        // 2^51 - 2^1
        t2 = t1.square();

        // 2^100 - 2^50
        for (int i = 1; i < 50; ++i) {
            t2 = t2.square();
        }

        // 2^100 - 2^0
        t2 = t2.multiply(t1);

        // 2^101 - 2^1
        t3 = t2.square();

        // 2^200 - 2^100
        for (int i = 1; i < 100; ++i) {
            t3 = t3.square();
        }

        // 2^200 - 2^0
        t2 = t3.multiply(t2);

        // 2^201 - 2^1
        t2 = t2.square();

        // 2^250 - 2^50
        for (int i = 1; i < 50; ++i) {
            t2 = t2.square();
        }

        // 2^250 - 2^0
        t1 = t2.multiply(t1);

        // 2^251 - 2^1
        t1 = t1.square();

        // 2^255 - 2^5
        for (int i = 1; i < 5; ++i) {
            t1 = t1.square();
        }

        // 2^255 - 21
        return t1.multiply(t0);
    }

    /**
     * Gets this field element to the power of $(2^{252} - 3)$.
     * This is a helper function for calculating the square root.
     * <p>
     * TODO-CR BR: I think it makes sense to have a sqrt function.
     *
     * @return This field element to the power of $(2^{252} - 3)$.
     */
    public FieldElement pow22523() {
        FieldElement t0, t1, t2;

        // 2 == 2 * 1
        t0 = square();

        // 4 == 2 * 2
        t1 = t0.square();

        // 8 == 2 * 4
        t1 = t1.square();

        // z9 = z1*z8
        t1 = multiply(t1);

        // 11 == 9 + 2
        t0 = t0.multiply(t1);

        // 22 == 2 * 11
        t0 = t0.square();

        // 31 == 22 + 9
        t0 = t1.multiply(t0);

        // 2^6 - 2^1
        t1 = t0.square();

        // 2^10 - 2^5
        for (int i = 1; i < 5; ++i) {
            t1 = t1.square();
        }

        // 2^10 - 2^0
        t0 = t1.multiply(t0);

        // 2^11 - 2^1
        t1 = t0.square();

        // 2^20 - 2^10
        for (int i = 1; i < 10; ++i) {
            t1 = t1.square();
        }

        // 2^20 - 2^0
        t1 = t1.multiply(t0);

        // 2^21 - 2^1
        t2 = t1.square();

        // 2^40 - 2^20
        for (int i = 1; i < 20; ++i) {
            t2 = t2.square();
        }

        // 2^40 - 2^0
        t1 = t2.multiply(t1);

        // 2^41 - 2^1
        t1 = t1.square();

        // 2^50 - 2^10
        for (int i = 1; i < 10; ++i) {
            t1 = t1.square();
        }

        // 2^50 - 2^0
        t0 = t1.multiply(t0);

        // 2^51 - 2^1
        t1 = t0.square();

        // 2^100 - 2^50
        for (int i = 1; i < 50; ++i) {
            t1 = t1.square();
        }

        // 2^100 - 2^0
        t1 = t1.multiply(t0);

        // 2^101 - 2^1
        t2 = t1.square();

        // 2^200 - 2^100
        for (int i = 1; i < 100; ++i) {
            t2 = t2.square();
        }

        // 2^200 - 2^0
        t1 = t2.multiply(t1);

        // 2^201 - 2^1
        t1 = t1.square();

        // 2^250 - 2^50
        for (int i = 1; i < 50; ++i) {
            t1 = t1.square();
        }

        // 2^250 - 2^0
        t0 = t1.multiply(t0);

        // 2^251 - 2^1
        t0 = t0.square();

        // 2^252 - 2^2
        t0 = t0.square();

        // 2^252 - 3
        return multiply(t0);
    }

    /**
     * Constant-time conditional move. Well, actually it is a conditional copy.
     * Logic is inspired by the SUPERCOP implementation at:
     *   https://github.com/floodyberry/supercop/blob/master/crypto_sign/ed25519/ref10/fe_cmov.c
     *
     * @param val the other field element.
     * @param b must be 0 or 1, otherwise results are undefined.
     * @return a copy of this if $b == 0$, or a copy of val if $b == 1$.
     */
    @Override
    public FieldElement cmov(FieldElement val, int b) {
        Ed25519FieldElement that = (Ed25519FieldElement) val;
        b = -b;
        int[] result = new int[10];
        for (int i = 0; i < 10; i++) {
            result[i] = this.t[i];
            int x = this.t[i] ^ that.t[i];
            x &= b;
            result[i] ^= x;
        }
        return new Ed25519FieldElement(this.f, result);
    }

    @Override
    public FieldElement carry() {
        int x1;
        int x2;
        int x3;
        int x4;
        int x5;
        int x6;
        int x7;
        int x8;
        int x9;
        int x10;
        int x11;
        int x12;
        int x13;
        int x14;
        int x15;
        int x16;
        int x17;
        int x18;
        int x19;
        int x20;
        int x21;
        int x22;
        x1 = (this.t[0]);
        x2 = ((x1 >>> 26) + (this.t[1]));
        x3 = ((x2 >>> 25) + (this.t[2]));
        x4 = ((x3 >>> 26) + (this.t[3]));
        x5 = ((x4 >>> 25) + (this.t[4]));
        x6 = ((x5 >>> 26) + (this.t[5]));
        x7 = ((x6 >>> 25) + (this.t[6]));
        x8 = ((x7 >>> 26) + (this.t[7]));
        x9 = ((x8 >>> 25) + (this.t[8]));
        x10 = ((x9 >>> 26) + (this.t[9]));
        x11 = ((x1 & 0x3ffffff) + ((x10 >> 25) * (byte) 0x13));
        x12 = ((byte)(x11 >>> 26) + (x2 & 0x1ffffff));
        x13 = (x11 & 0x3ffffff);
        x14 = (x12 & 0x1ffffff);
        x15 = ((byte)(x12 >>> 25) + (x3 & 0x3ffffff));
        x16 = (x4 & 0x1ffffff);
        x17 = (x5 & 0x3ffffff);
        x18 = (x6 & 0x1ffffff);
        x19 = (x7 & 0x3ffffff);
        x20 = (x8 & 0x1ffffff);
        x21 = (x9 & 0x3ffffff);
        x22 = (x10 & 0x1ffffff);
        int[] out1 = new int[10];
        out1[0] = x13;
        out1[1] = x14;
        out1[2] = x15;
        out1[3] = x16;
        out1[4] = x17;
        out1[5] = x18;
        out1[6] = x19;
        out1[7] = x20;
        out1[8] = x21;
        out1[9] = x22;
        return new Ed25519FieldElement(this.f, out1);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(t);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Ed25519FieldElement))
            return false;
        Ed25519FieldElement fe = (Ed25519FieldElement) obj;
        return 1==Utils.equal(toByteArray(), fe.toByteArray());
    }

    @Override
    public String toString() {
//        return "[Ed25519FieldElement val="+Utils.bytesToHex(toByteArray())+"]";
        StringBuilder sb = new StringBuilder();
        for (int i : t) sb.append(i & 0xFFFF_FFFFL).append(" ");
        return sb.toString();
    }
}

