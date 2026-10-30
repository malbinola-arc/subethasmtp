package org.subethamail.smtp.command;

import org.subethamail.smtp.util.ServerTestCase;

/**
 * @author Dave Moten
 */
public class BdatTest extends ServerTestCase
{

    public BdatTest(String name)
    {
        super(name);
    }

    public void testNeedMail() throws Exception
    {
        this.expect("220");

        this.send("HELO foo.com");
        this.expect("250");

        this.send("BDAT");
        this.expect("503 5.5.1 Error: need MAIL command");
    }

    public void testNeedRcpt() throws Exception
    {
        this.expect("220");

        this.send("HELO foo.com");
        this.expect("250");

        this.send("MAIL FROM: success@subethamail.org");
        this.expect("250");

        this.send("BDAT");
        this.expect("503 Error: need RCPT command");
    }

    public void testNoArguments() throws Exception
    {
        this.expect("220");

        this.send("HELO foo.com");
        this.expect("250");

        this.send("MAIL FROM: success@subethamail.org");
        this.expect("250");

        this.send("RCPT TO: success@subethamail.org");
        this.expect("250");

        this.send("BDAT");
        this.expect("503 Error: wrong syntax for BDAT command");
    }
    
    public void testBadSize() throws Exception
    {
        this.expect("220");

        this.send("HELO foo.com");
        this.expect("250");

        this.send("MAIL FROM: success@subethamail.org");
        this.expect("250");

        this.send("RCPT TO: success@subethamail.org");
        this.expect("250");

        this.send("BDAT hello");
        this.expect("503 Error: integer size expected after BDAT token");
    }
    
    public void testBadThirdTokenShouldBeLAST() throws Exception
    {
        this.expect("220");

        this.send("HELO foo.com");
        this.expect("250");

        this.send("MAIL FROM: success@subethamail.org");
        this.expect("250");

        this.send("RCPT TO: success@subethamail.org");
        this.expect("250");

        this.send("BDAT 123 BILBO");
        this.expect("503 Error: expected LAST but found BILBO");
    }
    
    public void testTooManyArguments() throws Exception
    {
        this.expect("220");

        this.send("HELO foo.com");
        this.expect("250");

        this.send("MAIL FROM: success@subethamail.org");
        this.expect("250");

        this.send("RCPT TO: success@subethamail.org");
        this.expect("250");

        this.send("BDAT 123 LAST BOO");
        this.expect("503 Error: too many arguments found for BDAT command");
    }
    
    public void testNegativeSize() throws Exception
    {
        this.expect("220");

        this.send("HELO foo.com");
        this.expect("250");

        this.send("MAIL FROM: success@subethamail.org");
        this.expect("250");

        this.send("RCPT TO: success@subethamail.org");
        this.expect("250");

        this.send("BDAT -1 LAST");
        this.expect("503 Error: size token after BDAT must be non-negative integer");
    }

    public void testZeroSize() throws Exception
    {
        this.expect("220");

        this.send("HELO foo.com");
        this.expect("250");

        this.send("MAIL FROM: success@subethamail.org");
        this.expect("250");

        this.send("RCPT TO: success@subethamail.org");
        this.expect("250");

        this.send("BDAT 0");
        this.expect("551 5.7.1 Error: Null BDAT request");
    }

}