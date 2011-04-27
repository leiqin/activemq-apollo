/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.apollo.openwire.command;

import org.apache.activemq.apollo.openwire.codec.OpenWireFormat;
import org.apache.activemq.apollo.openwire.support.MarshallingSupport;
import org.apache.activemq.apollo.openwire.support.OpenwireException;
import org.apache.activemq.apollo.openwire.support.Settings;
import org.fusesource.hawtbuf.Buffer;
import org.fusesource.hawtbuf.ByteArrayInputStream;
import org.fusesource.hawtbuf.ByteArrayOutputStream;

import java.io.*;
import java.util.HashMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * @openwire:marshaller code="28"
 */
public class ActiveMQTextMessage extends ActiveMQMessage {

    public static final byte DATA_STRUCTURE_TYPE = CommandTypes.ACTIVEMQ_TEXT_MESSAGE;

    protected String text;

    public Message copy() {
        ActiveMQTextMessage copy = new ActiveMQTextMessage();
        copy(copy);
        return copy;
    }

    private void copy(ActiveMQTextMessage copy) {
        super.copy(copy);
        copy.text = text;
    }

    public byte getDataStructureType() {
        return DATA_STRUCTURE_TYPE;
    }

    public String getJMSXMimeType() {
        return "jms/text-message";
    }

    public void setText(String text) throws OpenwireException {
        checkReadOnlyBody();
        this.text = text;
        setContent(null);
    }

    public String getText() throws OpenwireException {
        if (text == null && getContent() != null) {
            InputStream is = null;
            try {
                Buffer bodyAsBytes = getContent();
                if (bodyAsBytes != null) {
                    is = new ByteArrayInputStream(bodyAsBytes);
                    if (isCompressed()) {
                        is = new InflaterInputStream(is);
                    }
                    DataInputStream dataIn = new DataInputStream(is);
                    text = MarshallingSupport.readUTF8(dataIn);
                    dataIn.close();
                    setContent(null);
                }
            } catch (IOException ioe) {
                throw new OpenwireException(ioe);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        }
        return text;
    }

    public void beforeMarshall(OpenWireFormat wireFormat) throws IOException {
        super.beforeMarshall(wireFormat);

        Buffer content = getContent();
        if (content == null && text != null) {
            ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
            OutputStream os = bytesOut;
            if (Settings.enable_compression()) {
                compressed = true;
                os = new DeflaterOutputStream(os);
            }
            DataOutputStream dataOut = new DataOutputStream(os);
            MarshallingSupport.writeUTF8(dataOut, this.text);
            dataOut.close();
            setContent(bytesOut.toBuffer());
            //see https://issues.apache.org/activemq/browse/AMQ-2103
            this.text=null;
        }
    }

    public void clearBody() throws OpenwireException {
        super.clearBody();
        this.text = null;
    }

    public int getSize() {
        if (size == 0 && content == null && text != null) {
            size = getMinimumMessageSize();
            if (marshalledProperties != null) {
                size += marshalledProperties.getLength();
            }
            size = text.length() * 2;
        }
        return super.getSize();
    }
    
    public String toString() {
        try {
            String text = getText();
        	if (text != null && text.length() > 63) {
        		text = text.substring(0, 45) + "..." + text.substring(text.length() - 12);
        		HashMap<String, Object> overrideFields = new HashMap<String, Object>();
        		overrideFields.put("text", text);
        		return super.toString(overrideFields);
        	}
        } catch (OpenwireException e) {
        }
        return super.toString();
    }
}