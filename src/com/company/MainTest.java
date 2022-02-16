package com.company;

import org.junit.Assert;
import org.junit.Test;

import static com.company.Main.*;

public class MainTest {

    @Test
    public void cleanUpBlockTest() {
        Assert.assertEquals("","");
        Assert.assertEquals("C0FDE26388950C800000000000000000EEC0FC",cleanUpBlock("C0FDE26388950C800000000000000000EEC0FC"));
        Assert.assertEquals("C0FDE26388950C800000000000000000EEC0FC",cleanUpBlock("C0FBE0777763880903200000000000E5C0C0FDE26388950C800000000000000000EEC0FC"));
        Assert.assertEquals("C0FDE26388950C800000000000000000EEC0FC",cleanUpBlock("63880903200000000000E5C0C0FDE26388950C800000000000000000EEC0FC"));
    }

    @Test
    public void isValidBlockTest() {
        Assert.assertTrue(isValidBlock("C0FDE26388950C800000000000000000EEC0FC"));
        Assert.assertTrue(isValidBlock("C0FDE063887777000000000000000000B9C0FC"));
        Assert.assertTrue(isValidBlock("C0FDE063887777090000000000000000C2C0FC"));
        Assert.assertTrue(!isValidBlock("D0FDE063887777090000000000000000C2C0FC"));  // must start with C0
        Assert.assertTrue(!isValidBlock("C0FDE063887777090000000000000000C2C1FC"));  // must end with C0FC
    }

    @Test
    public void deEscapeBlockTest() {
        Assert.assertEquals("FDE26388950C800000000000000000EE",deEscapeBlock("FDE26388950C800000000000000000EE"));
        Assert.assertEquals("FDE2638895C0000EE",deEscapeBlock("FDE2638895DBDC000EE"));
        Assert.assertEquals("",deEscapeBlock(""));
        Assert.assertEquals("FDE2638895DB000EE",deEscapeBlock("FDE2638895DBDD000EE"));
    }

    @Test
    public void escapeBlockTest() {
        Assert.assertEquals("FDE26388950C800000000000000000EE",escapeBlock("FDE26388950C800000000000000000EE"));
        Assert.assertEquals("FDE2638895DBDC000EE",escapeBlock("FDE2638895C0000EE"));
        Assert.assertEquals("FDE2638895DBDD000EE",escapeBlock("FDE2638895DB000EE"));
    }

    @Test
    public void calculateChecksumTest() {
        Assert.assertEquals("B1",calculateChecksum("FDE06388777709051404D200000000"));
        Assert.assertEquals("B9",calculateChecksum("FDE06388777709051404DA00000000"));
        Assert.assertEquals("EE",calculateChecksum("FDE26388950C800000000000000000"));
        Assert.assertEquals("EE",calculateChecksum("F2E26388950C800000000000000000"));  // first byte doesn't count
    }

    @Test
    public void decodeAmpsTest() {
        Assert.assertEquals(6.0,decodeAmps("0258"),0);
        Assert.assertEquals(16.0,decodeAmps("0640"),0);
        Assert.assertEquals(32.0,decodeAmps("0C80"),0);
        Assert.assertEquals(28.66,decodeAmps("0B32"),0);
    }
}