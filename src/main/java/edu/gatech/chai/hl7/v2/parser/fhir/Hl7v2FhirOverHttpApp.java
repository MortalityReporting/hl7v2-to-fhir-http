package edu.gatech.chai.hl7.v2.parser.fhir;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.Bundle;
import org.json.JSONObject;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.hoh.hapi.server.HohServlet;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.protocol.ReceivingApplicationException;

public class Hl7v2FhirOverHttpApp extends HohServlet {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private HL7v23FhirR4Parser hl7v23FhirR4Parser;
	private FhirContext ctx;
	
	/**
	 * Initialise the servlet
	 */
	@Override
	public void init(ServletConfig theConfig) throws ServletException {
		
		/* Servlet should be initialized with an instance of
		 * ReceivingApplication, which handles incoming messages 
		 */
		setApplication(new MyApplication());
		hl7v23FhirR4Parser = new HL7v23FhirR4Parser();
		
		ctx = FhirContext.forR4();
	}

	/**
	 * The application does the actual processing
	 */
	private class MyApplication implements ReceivingApplication<Message>
	{

		private void sendFhir(Message theMessage) throws ReceivingApplicationException, HL7Exception {
			String requestUrl = System.getenv("FHIR_PROCESS_MESSAGE_URL");
			if (requestUrl == null || requestUrl.isEmpty()) {
				requestUrl = "http://localhost:8080/fhir";
			}
			IGenericClient client = ctx.newRestfulGenericClient(requestUrl);

			String authBasic = System.getenv("AUTH_BASIC");
			String authBearer = System.getenv("AUTH_BEARER");
			if (authBasic != null && !authBasic.isEmpty()) {
				String[] auth = authBasic.split(":");
				if (auth.length == 2) {
					client.registerInterceptor(new BasicAuthInterceptor(auth[0], auth[1]));
				}
			} else if (authBearer != null && !authBearer.isEmpty()) {
				client.registerInterceptor(new BearerTokenAuthInterceptor(authBearer));
			} else {
				client.registerInterceptor(new BasicAuthInterceptor("client", "secret"));				
			}

			List<IBaseBundle> bundles = hl7v23FhirR4Parser.executeParser(theMessage);
			try {
				for (IBaseBundle bundle : bundles) {
					String fhirJson = ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);
					JSONObject fhirJsonObject = new JSONObject(fhirJson);
					
					System.out.println(fhirJsonObject.toString());
					
					Bundle response = client.operation().processMessage().setMessageBundle(bundle).synchronous(Bundle.class).execute();
					if (response == null || response.isEmpty()) {
						throw new ReceivingApplicationException("Failed to send to FHIR message");
					}
					
				}
			} catch (Exception e) {
				throw new ReceivingApplicationException(e);
			}
		}
		
		/**
		 * processMessage is fired each time a new message 
		 * arrives. 
		 * 
		 * @param theMessage The message which was received
		 * @param theMetadata A map containing additional information about
		 *                    the message, where it came from, etc.  
		 */
		@Override
		public Message processMessage(Message theMessage, Map<String, Object> theMetadata) throws ReceivingApplicationException, HL7Exception {
			System.out.println("Received message:\n" + theMessage.encode());

			// .. process the message ..
			sendFhir(theMessage);
			
			/*
			 * Now reply to the message
			 */
			Message response;
			try {
				response = theMessage.generateACK();
			} catch (IOException e) {
				throw new ReceivingApplicationException(e);
			}
			
			/*
			 * If something goes horribly wrong, you can throw an 
			 * exception and an HTTP 500 error will be generated.
			 * However, it is preferable to return a normal HL7 ACK 
			 * message with an "AE" response code to note an error. 
			 */
			boolean somethingFailed = false;
			if (somethingFailed) {
				throw new ReceivingApplicationException("");
			}

			/*
			 * It is better to return an HL7 message with an AE response
			 * code. This will still be returned by the transport with
			 * an HTTP 500 status code, but an HL7 message will still 
			 * be propagated up. 
			 */
//			if (somethingFailed) {
//				try {
//					response = theMessage.generateACK("AE", new HL7Exception("There was a problem!!"));
//				} catch (IOException e) {
//					throw new ReceivingApplicationException(e);
//				}
//			}
			
			return response;
		}

		/**
		 * {@inheritDoc}
		 */
		public boolean canProcess(Message theMessage) {
			return true;
		}
		
	}
}
