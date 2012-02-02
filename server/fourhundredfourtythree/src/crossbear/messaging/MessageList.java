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

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateEncodingException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Iterator;
import java.util.Vector;

import crossbear.Database;

/**
 * A MessageList is a collection of Messages. On several occasions the Crossbear server send's more than one message to the client at the same time. These messages should be added to a MessageList
 * which provides a "getBytes()" function. This function generates a single byte[] out of the separate Messages.
 * 
 * @author Thomas Riedmaier
 * 
 */
public class MessageList {

	/**
	 * Try to retrieve the currently active HuntingTaskList from the local cache i.e. the HuntingTaskListCache-table
	 * 
	 * @param db The Database connection to use
	 * @return The byte[]-representation of the HuntingTaskList or null if there is no valid one in cache
	 * @throws SQLException
	 */
	private static byte[] getHTLFromDBCache(Database db) throws SQLException{
		
		Object[] params = { };
		ResultSet rs = db.executeQuery("SELECT * FROM HuntingTaskListCache LIMIT 1", params);

		// If the result is empty then there is no cache entry to return
		if (!rs.next()) {
			return null;
		}

		// If the cache entry is not valid anymore then there is nothing to return
		Timestamp validUntil = rs.getTimestamp("ValidUntil");
		if (validUntil.before(new Timestamp(System.currentTimeMillis())))
			return null;
		
		// If there is a cache entry that is currently valid: return its bytes.
		return rs.getBytes("Data");
	}

	/**
	 * Get the current HuntingTaskList and return it as MessageList. This function first attempts to load the HTL from the local cache and if that fails it generates a new one and stores it in the cache.
	 * 
	 * @param validity The validity that will be given to the HuntingTaskList if it is newly generated and added to the local cache
	 * @param db The Database connection to use
	 * @return The current HuntingTaskList as MessageList
	 * @throws InvalidKeyException
	 * @throws NoSuchAlgorithmException
	 * @throws NoSuchProviderException
	 * @throws SQLException
	 * @throws IOException
	 * @throws CertificateEncodingException
	 */
	public static MessageList getCurrentHuntingTaskList( long validity, Database db) throws InvalidKeyException, NoSuchAlgorithmException, NoSuchProviderException, SQLException, IOException, CertificateEncodingException {

		// Create a new MessageList
		MessageList ml = new MessageList();
		
		// Try to load the current HuntingTaskList from the cache
		byte[] cachedData = getHTLFromDBCache(db);
		if(cachedData!= null){
			//If that succeeded add it to the MessageList and return it
			ml.addAlreadyEncodedMessages(cachedData);
			return ml;
		}
		
		// If that failed calculate a new HTL
		Vector<HuntingTask> htv = HuntingTask.getAllActive(db);
		
		// Add all of its elements to the MessageList
		Iterator<HuntingTask> itr = htv.iterator();
		while(itr.hasNext()){
			ml.add(itr.next());
		}
		
		// Store the new HTL in the database
		storeHTLInDBCache(ml.getBytes(), validity, db);
		
		// Return the list
		return ml;
	}

	/**
	 * Store a HuntingTaskList in the local cache (i.e. the HuntingTaskListCache-table). The local HuntingTaskList cache is used to reduce the server load and to speed up the processing of the
	 * getHuntingTaskList.jsp
	 * 
	 * @param messageBytes The byte[]-representation of the HuntingTaskList (which is essentially a MessageList)
	 * @param validity The time in milliseconds that the entry should stay valid
	 * @param db The Database connection to use
	 * @throws SQLException
	 */
	private static void storeHTLInDBCache(byte[] messageBytes, long validity, Database db) throws SQLException {

		SQLException lastSQLException = null;

		/*
		 * "Update-or-Insert" requires two SQL statements. Since the state of the database might change in between the two statements transactions are used. Transactions might fail on commit. The only
		 * legal reason for that is that the entry that should be inserted has already been inserted in the meantime. In that case try updating that entry and if that succeeded go on. If that failed
		 * again then there is a real problem and an exception is thrown.
		 */
		db.setAutoCommit(false);
		for (int i = 0; i < 2; i++) {
			try {
				
				// First: Try to update an existing entry
				Object[] params = { messageBytes, new Timestamp(System.currentTimeMillis() + validity) };
				int updatedRows = db.executeUpdate("UPDATE HuntingTaskListCache SET Data = ?, ValidUntil = ?", params);

				// If there isn't any try to insert a new one.
				if (updatedRows == 0) {
					db.executeInsert("INSERT INTO HuntingTaskListCache (Data,ValidUntil) VALUES (?,?)", params);
				}

				// Try to commit the changes
				db.commit();
				
				// Reenable auto-commit
				db.setAutoCommit(true);
				return;
			} catch (SQLException e) {
				
				// Commit failed. If that was the first time: Try again
				db.rollback();
				lastSQLException = e;
			}
		}
		throw lastSQLException;

	}

	// The List of Messages that have been added to this MessageList
	private Vector<Message> messages = new Vector<Message>();

	// The List of Messages that have been added to this MessageList as byte[]s
	private byte[] encodedMessages = new byte[0];

	/**
	 * Add a single Message to the MessageList
	 * 
	 * @param message The message to add
	 */
	public void add(Message message) {
		messages.add(message);
	}

	
	/**
	 * In case Messages are not generated freshly but read from a cache they will be in their byte[]-representation. These Messages can be added to a MessageList by calling this function.
	 * 
	 * @param messagesToAdd The byte[]-representation of the Messages to add
	 */
	public void addAlreadyEncodedMessages(byte[] messagesToAdd) {

		// Create an array that's big enough to hold all new encoded Messages as well as all old encoded Messages
		byte[] buffer = new byte[messagesToAdd.length + encodedMessages.length];

		// Copy the old encoded Messages and the new encoded Messages into that array
		System.arraycopy(encodedMessages, 0, buffer, 0, encodedMessages.length);
		System.arraycopy(messagesToAdd, 0, buffer, encodedMessages.length, messagesToAdd.length);

		// Store the result
		encodedMessages = buffer;
	}
	
	
	/**
	 * Generates a single byte[] out of the separate Messages and the encodedMessages
	 * 
	 * @return A byte[] that is the concatenation of the byte[]-representation of all Messages that were added to this MessageList
	 * @throws InvalidKeyException
	 * @throws NoSuchAlgorithmException
	 * @throws NoSuchProviderException
	 * @throws IOException
	 * @throws SQLException
	 * @throws CertificateEncodingException
	 */
	public byte[] getBytes() throws InvalidKeyException, NoSuchAlgorithmException, NoSuchProviderException, IOException, SQLException, CertificateEncodingException {

		// Firstly get the Messages' bytes (of the Messages that are not yet encoded) and calculate the length of all concatenated messages
		byte[][] messagesBytes = new byte[messages.size()][];
		int totalLength = 0;
		for (int i = 0; i < messagesBytes.length; i++) {
			messagesBytes[i] = messages.get(i).getBytes();
			totalLength += messagesBytes[i].length;
		}

		// Secondly create a byte array that's long enough to hold all messages
		byte[] re = new byte[totalLength + encodedMessages.length];
		int currentpos = 0;

		// Now copy the Not-Yet-Encoded-Messages' bytes into that long array
		for (int i = 0; i < messagesBytes.length; i++) {
			System.arraycopy(messagesBytes[i], 0, re, currentpos, messagesBytes[i].length);
			currentpos += messagesBytes[i].length;
		}

		// And finally append the already encoded messages and return the whole thing
		System.arraycopy(encodedMessages, 0, re, currentpos, encodedMessages.length);
		return re;

	}

}