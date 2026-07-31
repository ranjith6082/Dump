package com.serv.gc.GenesysCloudMonitoringApp.service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import om.serv.gc.GenesysCloudMonitoringApp.model.AlertMessageTemplate;

public class NotificationAPIService {
	
	@Value("${gc.me.mailclientid}")
	private String clientid;

	@Value("${gc.me.mailclientSecret}")
	private String clientsecret;
	
	@Value("${gc.me.mailscope}")
	private String scope;
	
	@Value("${gc.office365TokenURL}")
	private String office365TokenURL;
	
	@Value("${gc.fairusageurl}")
	private String fairUsageURL;
	
	private final  RestTemplate restTemplate;
	private Logger logger = LoggerFactory.getLogger(NotificationService.class);
	
	public OAuthResponse getAccessToken() {

		// Set parameters (for example, if using client credentials grant)
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("grant_type", "client_credentials");
		params.add("client_id", clientid);
		params.add("client_secret", clientsecret);
		params.add("scope", scope);

		// Set headers to send content as application/x-www-form-urlencoded
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		// Create HttpEntity with parameters and headers
		HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

		// Send POST request and get the response
		ResponseEntity<OAuthResponse> response = restTemplate.exchange(office365TokenURL, HttpMethod.POST, entity, OAuthResponse.class);

		// Return the response body (OAuthResponse object)
		return response.getBody();
	}

	
	
	public String sendEmail(String accessToken,String orgId,List<AlertMessageTemplate> alertMessagelist,List<String> emaillist,String alertType) {
		// URL to send email (replace with actual URL)
		String url = "https://graph.microsoft.com/v1.0/users/gcusagenotification@servion.com/sendMail";  // Replace with actual email API endpoint

		// Create the email request object (message details)
		EmailRequest emailRequest = new EmailRequest();

		// Set the email details
		EmailRequest.Message message = new EmailRequest.Message();
		message.setSubject("Genesys Cloud Usage Alert for " + orgId);

		EmailRequest.Message.Body body = new EmailRequest.Message.Body();
		body.setContentType("HTML");
		body.setContent(setMessageBody(alertMessagelist, orgId, alertType));  // Assuming this method constructs the message body
		message.setBody(body);

		List<String> receiverlist=new ArrayList<>();
		List<String> ccReceiverlist=new ArrayList<>();

		for(String email:emaillist) {
			if(email.contains("servion")) {
				ccReceiverlist.add(email);
			}else {
				receiverlist.add(email);
			}
		}


		// Create a list of recipients for "To" field
		List<EmailRequest.Message.Recipient> toRecipients = new ArrayList<>();
		if(receiverlist!=null) {
			for (String receiver : receiverlist) {
				EmailRequest.Message.Recipient recipient = new EmailRequest.Message.Recipient();

				// Create the EmailAddress object for each "To" recipient
				EmailRequest.Message.Recipient.EmailAddress emailAddress = new EmailRequest.Message.Recipient.EmailAddress();
				logger.info("Receiver Email: " + receiver);
				emailAddress.setAddress(receiver);

				// Set the single EmailAddress in the Recipient
				recipient.setEmailAddress(emailAddress);

				// Add the Recipient to the "To" list
				toRecipients.add(recipient);
			}
		}

		// Create a list of recipients for "CC" field
		List<EmailRequest.Message.Recipient> ccRecipients = new ArrayList<>();
		if(ccReceiverlist!=null) {
			for (String ccReceiver : ccReceiverlist) {
				EmailRequest.Message.Recipient recipient = new EmailRequest.Message.Recipient();

				// Create the EmailAddress object for each "CC" recipient
				EmailRequest.Message.Recipient.EmailAddress emailAddress = new EmailRequest.Message.Recipient.EmailAddress();
				logger.info("CC Receiver Email: " + ccReceiver);
				emailAddress.setAddress(ccReceiver);

				// Set the single EmailAddress in the Recipient
				recipient.setEmailAddress(emailAddress);

				// Add the Recipient to the "CC" list
				ccRecipients.add(recipient);
			}
		}

		
		
		
		if(toRecipients!=null && toRecipients.size()>0) {
		// Set the "To" and "CC" recipients in the message
		message.setToRecipients(toRecipients);
		message.setCcRecipients(ccRecipients);  // Add CC recipients

		// Set the message in the email request
		emailRequest.setMessage(message);

		// Set headers including Bearer token
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Authorization", "Bearer " + accessToken);

		// Create HttpEntity with email request and headers
		HttpEntity<EmailRequest> entity = new HttpEntity<>(emailRequest, headers);

		// Send POST request
		ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
		// Return response as string (or you could parse it to an object if needed)
				return response.getBody();
		}else {
			logger.info("To Recipents not available, Hence not sending mail");
			return "To Recipents not available";
		}
		
	}


	private String setMessageBody(List<AlertMessageTemplate> alertMessagelist,String orgName,String alertType) {
		StringBuilder htmlContent = new StringBuilder();
		try {
			SimpleDateFormat sdf=new SimpleDateFormat("dd-MMM-yyyy");
			String date=sdf.format(new Date());

			htmlContent.append("<html><body>");
			htmlContent.append("<h2>").append("Genesys Usage "+alertType+" Alert for "+orgName+" - "+date).append("</h2>");
			htmlContent.append("<table style='border-collapse:collapse; text-align:center;'>");

			htmlContent.append("<tr style='background-color:#6FA1D2; color:#ffffff;'>");
			htmlContent.append("<th style='border-color:#5c87b2; border-style:solid; border-width:thin; padding: 5px;'>").append("S.NO").append("</th>");
			htmlContent.append("<th style='border-color:#5c87b2; border-style:solid; border-width:thin; padding: 5px;'>").append("Name").append("</th>");
			htmlContent.append("<th style='border-color:#5c87b2; border-style:solid; border-width:thin; padding: 5px;'>").append("Prepay/Bundled").append("</th>");
			htmlContent.append("<th style='border-color:#5c87b2; border-style:solid; border-width:thin; padding: 5px;'>").append("Usage").append("</th>");
			htmlContent.append("<th style='border-color:#5c87b2; border-style:solid; border-width:thin; padding: 5px;'>").append("LimitedExceeded").append("</th>");
			htmlContent.append("</tr>");


			int i=1;
			for(AlertMessageTemplate alertMessage:alertMessagelist)
			{
				htmlContent.append("<tr style='color:#555555;'>");
				htmlContent.append("<td style='border-color:#5c87b2; border-style:solid; border-width:thin; padding: 5px;'>").append(i).append("</td>");
				htmlContent.append("<td style='border-color:#5c87b2; border-style:solid; border-width:thin; padding: 5px;'>").append(alertMessage.getName()).append("</td>");
				htmlContent.append("<td style='border-color:#5c87b2; border-style:solid; border-width:thin; padding: 5px;'>").append(alertMessage.getPrepayQuantity()).append("</td>");
				htmlContent.append("<td style='border-color:#5c87b2; border-style:solid; border-width:thin; padding: 5px;'>").append(alertMessage.getUsageQuantity()).append("</td>");
				if(alertMessage.getUsagelimit()!=null && alertMessage.getUsagelimit().equalsIgnoreCase("YES")) {
					htmlContent.append("<td style='border-color:#5c87b2; color: red; border-style:solid; border-width:thin; padding: 5px;'>").append(alertMessage.getUsagelimit()).append("</td>");
				}else {
					htmlContent.append("<td style='border-color:#5c87b2; border-style:solid; border-width:thin; padding: 5px;'>").append(alertMessage.getUsagelimit()).append("</td>");	
				}
				htmlContent.append("</tr>");
				i++;
			}
			htmlContent.append("</table>");
			
			htmlContent.append("<br><br>");
			htmlContent.append("<a href="+fairUsageURL+">For Genesys Cloud Usage Details please click here</a>");
			
			htmlContent.append("<br><br>");
			htmlContent.append("Regards<br>");
			htmlContent.append("Servion Team<br><br>");
			htmlContent.append("</body></html>");

			logger.info("HTML Content :"+htmlContent);

		}catch(Exception mex) {
			StringWriter str=new StringWriter();
			mex.printStackTrace(new PrintWriter(str));
			logger.error("Exception :"+str.toString());
		}
		return htmlContent.toString();
	}

	
	
}
