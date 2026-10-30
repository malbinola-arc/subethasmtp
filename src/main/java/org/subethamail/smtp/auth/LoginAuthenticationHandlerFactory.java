package org.subethamail.smtp.auth;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.StringTokenizer;

import org.subethamail.smtp.AuthenticationHandler;
import org.subethamail.smtp.AuthenticationHandlerFactory;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.RejectException;
import org.subethamail.smtp.internal.util.TextUtils;

/**
 * Implements the SMTP AUTH LOGIN mechanism.<br>
 * You are only required to plug your UsernamePasswordValidator implementation
 * for username and password validation to take effect.
 * <p>
 * LOGIN is an obsolete authentication method which has no formal specification.
 * There is an expired IETF draft for informational purposes. A Microsoft
 * document can also be found, which intends to specify the LOGIN mechanism. The
 * latter is not entirely compatible, neither with the IETF draft nor with RFC
 * 4954 (SMTP Service Extension for Authentication). However this implementation
 * is likely usable with clients following any of the two documents.
 * 
 * @see <a href="http://tools.ietf.org/html/draft-murchison-sasl-login-00">The
 *      LOGIN SASL Mechanism</a>
 * @see <a
 *      href="http://download.microsoft.com/download/5/d/d/5dd33fdf-91f5-496d-9884-0a0b0ee698bb/%5BMS-XLOGIN%5D.pdf">[MS-XLOGIN]</a>
 * 
 * @author Marco Trevisan <mrctrevisan@yahoo.it>
 * @author Jeff Schnitzer
 */
public final class LoginAuthenticationHandlerFactory implements AuthenticationHandlerFactory
{
	private static final List<String> MECHANISMS = new ArrayList<>(1);
	static {
		MECHANISMS.add("LOGIN");
	}

	private final UsernamePasswordValidator helper;

	public LoginAuthenticationHandlerFactory(UsernamePasswordValidator helper)
	{
		this.helper = helper;
	}

	@Override
    public List<String> getAuthenticationMechanisms()
	{
		return MECHANISMS;
	}

	@Override
    public AuthenticationHandler create()
	{
		return new Handler();
	}

	/**
	 */
	final class Handler implements AuthenticationHandler
	{
		private String username;

		@Override
		public Optional<String> auth(String clientInput, MessageContext context) throws RejectException
		{
			StringTokenizer stk = new StringTokenizer(clientInput);
			if (stk.hasMoreTokens() && stk.nextToken().trim().equalsIgnoreCase("AUTH"))
			{
				if (!stk.nextToken().trim().equalsIgnoreCase("LOGIN"))
				{
					// Mechanism mismatch
					throw new RejectException(504, "AUTH mechanism mismatch");
				}

				if (stk.hasMoreTokens())
				{
					// The client submitted an initial response, which should be
					// the username.
					// .Net's built in System.Net.Mail.SmtpClient sends its
					// authentication this way (and this way only).
					byte[] decoded;
					try
					{
						decoded = Base64.getDecoder().decode(stk.nextToken());
					} catch (IllegalArgumentException x)
					{
						throw new RejectException(501, /* 5.5.4 */
								"Invalid command argument, not a valid Base64 string");
					}
					username = TextUtils.getStringUtf8(decoded);

					return Optional.of("334 "
							+ Base64.getEncoder().encodeToString(
									TextUtils.getAsciiBytes("Password:")));
				} else {
					return Optional.of("334 "
							+ Base64.getEncoder().encodeToString(
									TextUtils.getAsciiBytes("Username:")));
				}
			}

			byte[] decoded;
			try 
			{
				decoded = Base64.getDecoder().decode(clientInput);
			} catch (IllegalArgumentException x)
			{
				throw new RejectException(501, /* 5.5.4 */
					"Invalid command argument, not a valid Base64 string");
			}

			if (this.username == null) {
				this.username = TextUtils.getStringUtf8(decoded);
				return Optional.of("334 "
						+ Base64.getEncoder().encodeToString(
								TextUtils.getAsciiBytes("Password:")));
			}


			String password = TextUtils.getStringUtf8(decoded);
			try
			{
				LoginAuthenticationHandlerFactory.this.helper.login(this.username, password, context);
			}
			catch (LoginFailedException lfe)
			{
				throw new RejectException(535, /*5.7.8*/
						"Authentication credentials invalid");
			}

			return Optional.empty();
		}

		/* */
		@Override
        public Object getIdentity()
		{
			return this.username;
		}
	}
}
