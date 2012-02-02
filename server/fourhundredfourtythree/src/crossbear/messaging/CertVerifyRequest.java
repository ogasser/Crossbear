/*
 * Copyright (c) 2011, Thomas Riedmaier, TU M�nchen
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Crossbear nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THOMAS RIEDMAIER BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package crossbear.messaging;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import crossbear.CertificateManager;

/**
 * A CertVerifyRequest-message is issued by the client to request the verification of a certificate that it obtained from a server. 
 * 
 * The structure of the CertVerifyRequest-message is
 * - Header
 * - Certificate (DER-encoding)
 * - Server that sent the certificate in the format HostName|HostIP|HostPort
 * 
 * The CertVerifyRequest-class stores this message and additionally the IP that sent the request-message and the IP that received the request-message.
 * 
 * @author Thomas Riedmaier
 *
 */
public class CertVerifyRequest extends Message {

	// Regex to validate Hostnames according to RFC 952 and RFC 1123
	private static final String validHostnameRegex = "^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z]|[A-Za-z][A-Za-z0-9\\-]*[A-Za-z0-9])$";

	/**
	 * Read a CertVerifyRequest-message from a InputStream. During the reading a lot of checks on the validity of the supplied data are performed. If one of them fails an exception is thrown.
	 * 
	 * @param in The InputStream to read the CertVerifyRequest from
	 * @param remoteAddr The IP address of the client that sent the CertVerifyRequest
	 * @param localAddr The IP of the local interface that received the CertVerifyRequest
	 * @return A CertVerifyRequest containing the information read from the InputStream (i.e. the CertVerifyRequest-message) as well as the requesting and the receiving IP.
	 * @throws IOException
	 * @throws CertificateException
	 */
	public static CertVerifyRequest readFromStream(InputStream in, String remoteAddr, String localAddr) throws IOException, CertificateException  {

		// cvr is the CertVerifyRequest that will be returned
		CertVerifyRequest cvr = new CertVerifyRequest();

		// Add the IP that sent the request-message and the IP that received it.
		cvr.setLocalAddr(InetAddress.getByName(localAddr));
		cvr.setRemoteAddr(InetAddress.getByName(remoteAddr));

		// Now Parse the InputStream
		BufferedInputStream bin = new BufferedInputStream(in);

		// First Verify the message is actually of type MESSAGE_TYPE_CERT_VERIFY_REQUEST
		int messageType = bin.read();
		if (messageType != Message.MESSAGE_TYPE_CERT_VERIFY_REQUEST) {
			throw new IllegalArgumentException("The provided messageType " + messageType + " was not expected");
		}

		// Then read the message length field
		byte[] messageLengthB = new byte[2];
		if(bin.read(messageLengthB, 0, 2) != 2){
			throw new IOException("Reached unexpected end of stream while extracting message length.");
		}
		int messageLength = Message.byteArrayToInt(messageLengthB);
		

		// Try to extract a X.509-Certificate from the InputStream
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		cvr.setCert((X509Certificate) cf.generateCertificate(bin));

		// Read the message's remainder. It should be of the format "HostName|HostIP|HostPort". Therefore it can be split into an array of size three.
		String[] host = Message.readNCharsFromStream(bin, messageLength - 3 - cvr.getCert().getEncoded().length).split("\\|");

		// Assert that the host-parameter actually consists of three parts.
		if (host.length != 3) {
			throw new IllegalArgumentException("The host-parameter could not be split in three parts.");
		}

		// The Hostname should match the validHostnameRegex and it's length should be something between 3 and 2042 since this is the maximum of the database "Host" column (therefore "HostPort" has a maximum of 2048)
		if (host[0].length() <= 3 || host[0].length() >= 2042 || !host[0].matches(validHostnameRegex)) {
			throw new IllegalArgumentException("The provided hostname is not valid: " + host[0]);
		}

		// If the Hostname is valid: store it in the cvr-Object
		cvr.setHostName(host[0]);

		// Check if the second parameter is a valid IP-Address ...
		if(!isValidIPAddress(host[1])){
			throw new IllegalArgumentException("The provided ip is not valid: " + host[1]);
		}
		
		// ... and if it is: store it in the cvr-Object
		cvr.setHostIP(InetAddress.getByName(host[1])); 

		// Cast the third parameter into a Integer ...
		int port = Integer.valueOf(host[2]);

		// ... and check if it is a valid 16 bit Integer > 0
		if (port <= 0 && port >= (1 << 16)) {
			throw new IllegalArgumentException("The provided port is outside the valid range: " + port);
		}

		// If it is: store it in the cvr-Object
		cvr.setHostPort(port);

		return cvr;
	}
	
	// The certificate that has been sent by the client
	private X509Certificate cert = null;
	
	// The name of the Host from which the certificate has been received
	private String hostName = "";
	
	// The ip of the Host from which the certificate has been received
	private InetAddress hostIP = null;

	// The port of the Host from which the certificate has been received
	private int hostPort = 0;
	
	// The IP that sent the CertVerifyRequest-message
	private InetAddress remoteAddr = null;
	
	//The IP that received the CertVerifyRequest-message
	private InetAddress localAddr = null;

	/**
	 * Create a new Message of Type MESSAGE_TYPE_CERT_VERIFY_REQUEST
	 */
	public CertVerifyRequest()  {
		super(Message.MESSAGE_TYPE_CERT_VERIFY_REQUEST);
	}

	/**
	 * @return The certificate that has been sent by the client
	 */
	public X509Certificate getCert() {
		return cert;
	}

	/**
	 * Under certain circumstances the client sends duplicate CertVerifyRequest-messages. Therefore CertVerifyResults are cached and resent on duplicate CertVerifyRequest-messages. The KEY of the
	 * CertVerifyResultCache-table is the hash of the CertVerifyRequest. This hash is calculated here.
	 * 
	 * @return The hash of the CertVerifyRequest-Object
	 * @throws CertificateEncodingException
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 */
	public byte[] getHash() throws CertificateEncodingException, IOException, NoSuchAlgorithmException{
		
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		
		// Write all elements of the CertVerifyRequest-message to the buffer
		writeContent(buffer);
		
		// Write the remote and local IP-addresses into the buffer
		buffer.write(remoteAddr.getAddress());
		buffer.write(localAddr.getAddress());
		
		// Calculate the SHA256-hash of that buffer and return it
		return CertificateManager.SHA256(buffer.toByteArray());
		
	}
	
	/**
	 * @return The IP of the Host from which the certificate has been received
	 */
	public InetAddress getHostIP() {
		return hostIP;
	}

	/**
	 * @return The name of the Host from which the certificate has been received
	 */
	public String getHostName() {
		return hostName;
	}

	/**
	 * @return The port of the Host from which the certificate has been received
	 */
	public int getHostPort() {
		return hostPort;
	}

	/**
	 * @return The IP that received the CertVerifyRequest-message
	 */
	public InetAddress getLocalAddr() {
		return localAddr;
	}

	/**
	 * @return The IP that sent the CertVerifyRequest-message
	 */
	public InetAddress getRemoteAddr() {
		return remoteAddr;
	}

	/**
	 * @param cert The certificate that has been sent by the client
	 */
	public void setCert(X509Certificate cert) {
		this.cert = cert;
	}

	/**
	 * @param hostIP The IP of the Host from which the certificate has been received
	 */
	public void setHostIP(InetAddress hostIP) {
		this.hostIP = hostIP;
	}

	/**
	 * @param hostName The name of the Host from which the certificate has been received
	 */
	public void setHostName(String hostName) {
		this.hostName = hostName;
	}

	/**
	 * @param hostPort The port of the Host from which the certificate has been received
	 */
	public void setHostPort(int hostPort) {
		this.hostPort = hostPort;
	}

	/**
	 * @param localAddr The IP that received the CertVerifyRequest-message
	 */
	public void setLocalAddr(InetAddress localAddr) {
		this.localAddr = localAddr;
	}

	/**
	 * @param remoteAddr The IP that sent the CertVerifyRequest-message
	 */
	public void setRemoteAddr(InetAddress remoteAddr) {
		this.remoteAddr = remoteAddr;
	}

	/* (non-Javadoc)
	 * @see crossbear.Message#writeContent(java.io.OutputStream)
	 */
	@Override
	protected void writeContent(OutputStream out) throws CertificateEncodingException, IOException {

		out.write(cert.getEncoded());

		out.write(new String(hostName + "|" + hostIP.getHostAddress() + "|" + String.valueOf(hostPort)).getBytes());

	}

}