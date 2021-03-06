/*******************************************************************************
 * Copyright (c) 2013 Dan Brough dan@danbrough.org. All rights reserved. 
 * This program and the accompanying materials are made available under the 
 * terms of the GNU Public License v3.0 which accompanies this distribution, 
 * and is available at http://www.gnu.org/licenses/gpl.html
 * 
 ******************************************************************************/
package org.danbrough.mega;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.regex.Pattern;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.JsonElement;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonWriter;

public class Crypto {
  public static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

  private static final Crypto INSTANCE = new Crypto();

  public static Crypto getInstance() {
    return INSTANCE;
  }

  public byte[] a32_to_bytes(int a[]) {

    byte b[] = new byte[a.length * 4];

    for (int i = 0; i < a.length * 4; i++)
      b[i] = (byte) ((a[i >> 2] >>> (24 - (i & 3) * 8)) & 255);
    return b;
  }

  public String bigToString(BigInteger b) {
    String hex = toHex(b.toByteArray());
    if (hex.startsWith("00"))
      hex = hex.substring(2);
    // if (hex.length() % 4 != 0)
    // hex = "00" + hex;
    return hex;
  }

  public int[] bytes_to_a32(byte b[]) {
    int a[] = new int[(b.length + 3) >> 2];
    for (int i = 0; i < b.length; i++) {
      a[i >> 2] |= ((0x000000ff & b[i]) << (24 - (i & 3) * 8));
    }
    return a;
  }

  public String stringhash(String s, byte key[]) {
    long startTime = System.currentTimeMillis();
    Cipher c = createCipher(key, Cipher.ENCRYPT_MODE);

    int s32[] = null;
    try {
      s32 = bytes_to_a32(s.getBytes("ISO-8859-1"));
    } catch (UnsupportedEncodingException e1) {
      e1.printStackTrace();
    }

    int h32[] = { 0, 0, 0, 0 };

    for (int i = 0; i < s32.length; i++)
      h32[i & 3] ^= s32[i];

    for (int i = 0; i < 16384; i++) {
      try {
        h32 = bytes_to_a32(c.doFinal(a32_to_bytes(h32)));
      } catch (Exception e) {
        e.printStackTrace();
        return null;
      }
    }
    String result = base64urlencode(a32_to_bytes(new int[] { h32[0], h32[2] }));
    System.out.println("stringhash took "
        + (System.currentTimeMillis() - startTime));
    return result;
  }

  public String base64urlencode(byte[] data) {
    return Base64.encodeToString(data, Base64.NO_WRAP | Base64.URL_SAFE
        | Base64.NO_PADDING);
  }

  public byte[] base64urldecode(String data) {
    return Base64.decode(data, Base64.NO_WRAP | Base64.URL_SAFE
        | Base64.NO_PADDING);
  }

  public int[] aes_cbc_encrypt_a32(int data[], int key[]) {
    return bytes_to_a32(aes_cbc_encrypt(a32_to_bytes(key), a32_to_bytes(data)));
  }

  public int[] aes_cbc_decrypt_a32(int data[], int key[]) {
    return bytes_to_a32(aes_cbc_decrypt(a32_to_bytes(key), a32_to_bytes(data)));
  }

  public byte[] aes_cbc_encrypt(byte key[], byte[] data) {
    try {
      return createCipher(key, Cipher.ENCRYPT_MODE).doFinal(data);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public byte[] aes_cbc_decrypt(byte key[], byte[] data) {
    try {
      return createCipher(key, Cipher.DECRYPT_MODE).doFinal(data);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public byte[] prepareKey(String password) {
    try {
      return a32_to_bytes(prepare_key(bytes_to_a32(password
          .getBytes("ISO-8859-1"))));
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
      return null;
    }
  }

  public int[] prepare_key(int a[]) {

    Cipher aes[] = new Cipher[a.length / 4 + (a.length % 4 == 0 ? 0 : 1)];
    int pkey[] = { 0x93C467E3, 0x7DB0C7A4, 0xD1BE3F81, 0x0152CB56 };
    int k = 0;

    for (int j = 0; j < a.length; j += 4) {
      int key[] = { 0, 0, 0, 0 };
      for (int i = 0; i < 4; i++)
        if (i + j < a.length)
          key[i] = a[i + j];

      aes[k++] = createCipher(a32_to_bytes(key), Cipher.ENCRYPT_MODE);
    }

    for (int r = 0; r < 65536; r++) {
      for (int j = 0; j < aes.length; j++) {
        try {
          pkey = bytes_to_a32(aes[j].doFinal(a32_to_bytes(pkey)));
        } catch (IllegalBlockSizeException e) {
          e.printStackTrace();
        } catch (BadPaddingException e) {
          e.printStackTrace();
        }
      }
    }
    return pkey;

  }

  public String toHex(byte[] buf) {
    if (buf == null)
      return "";
    StringBuffer result = new StringBuffer(2 * buf.length);
    for (int i = 0; i < buf.length; i++) {
      appendHex(result, buf[i]);
    }
    return result.toString();
  }

  private final String HEX = "0123456789abcdef";

  public void appendHex(StringBuffer sb, byte b) {
    sb.append(HEX.charAt((b >> 4) & 0x0f)).append(HEX.charAt(b & 0x0f));
  }

  public byte[] fromHex(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character
          .digit(s.charAt(i + 1), 16));
    }
    return data;
  }

  public Cipher createCipher(byte key[], int mode) {
    try {

      IvParameterSpec ivSpec = new IvParameterSpec(new byte[] { 0, 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });

      SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
      Cipher cipher = Cipher.getInstance("AES/CBC/NOPADDING");
      cipher.init(mode, keySpec, ivSpec);
      return cipher;

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected String crypto_handleauth(String h, UserContext ctx) {
    // return a32_to_base64(encrypt_key(u_k_aes,str_to_a32(h+h)));

    try {
      return base64urlencode(enccrypt_key((h + h).getBytes("ISO-8859-1"),
          ctx.getMasterKey()));
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
      return null;
    }
  }

  public static final Pattern EMAIL_ADDRESS = Pattern
      .compile("[a-zA-Z0-9\\+\\.\\_\\%\\-\\+]{1,256}" + "\\@"
          + "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}" + "(" + "\\."
          + "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25}" + ")+");

  protected boolean checkEmailAddress(CharSequence email) {
    return EMAIL_ADDRESS.matcher(email).matches();
  }

  // return a big int from bytes, (excluding the first 2 which are a length
  // prefix)
  public BigInteger mpi2big(byte b[]) throws IOException {
    byte bb[] = new byte[b.length - 2];
    System.arraycopy(b, 2, bb, 0, bb.length);

    return new BigInteger(toHex(bb), 16);
  }

  public byte[] process_key(byte a[], byte key[], int mode) {
    byte result[] = new byte[a.length];
    Cipher cipher = createCipher(key, mode);
    for (int i = 0; i < a.length; i += 16) {
      byte copy[] = new byte[16];
      System.arraycopy(a, i, copy, 0, 16);
      try {
        byte part[] = cipher.doFinal(a, i, 16);
        System.arraycopy(part, 0, result, i, 16);
      } catch (Exception e) {
        e.printStackTrace();
        return new byte[] {};
      }
    }
    return result;
  }

  public byte[] decrypt_key(byte a[], byte key[]) {
    return process_key(a, key, Cipher.DECRYPT_MODE);
  }

  public byte[] enccrypt_key(byte a[], byte key[]) {
    return process_key(a, key, Cipher.ENCRYPT_MODE);
  }

  public BigInteger RSAdecrypt(BigInteger m, BigInteger d, BigInteger p,
      BigInteger q, BigInteger u) {
    // var xp = bmodexp(bmod(m,p), bmod(d,bsub(p,[1])), p);
    BigInteger xp = m.mod(p).modPow(d.mod(p.subtract(BigInteger.ONE)), p);
    // var xq = bmodexp(bmod(m,q), bmod(d,bsub(q,[1])), q);
    BigInteger xq = m.mod(q).modPow(d.mod(q.subtract(BigInteger.ONE)), q);

    // var t=bsub(xq,xp);
    BigInteger t = xq.subtract(xq);

    // if(t.length==0)
    if (t.equals(BigInteger.ZERO)) {
      // {
      // t=bsub(xp,xq);
      t = xp.subtract(xq);
      // t=bmod(bmul(t, u), q);
      t = t.multiply(u).mod(q);

      // t=bsub(q,t);
      t = q.subtract(t);
      // }
    } else {
      // t=bmod(bmul(t, u), q);
      t = t.multiply(u).mod(q);
    }

    // return badd(bmul(t,p), xp);
    return t.multiply(p).add(xp);
  }

  public String toPrettyString(JsonElement o) {

    StringWriter out = new StringWriter();
    JsonWriter writer = new JsonWriter(out);
    writer.setIndent(" ");
    writer.setLenient(true);
    try {
      Streams.write(o, writer);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return out.toString();
  }
}
