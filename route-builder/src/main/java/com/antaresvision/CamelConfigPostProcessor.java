package com.antaresvision;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

import com.model.config.Channels;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

public class CamelConfigPostProcessor implements Processor {
	// Dev ref: https://www.thoughts-on-java.org/generate-your-jaxb-classes-in-second/
		
	public static final Logger logger = LoggerFactory.getLogger(CamelConfigPostProcessor.class);

	@Override
	public void process(Exchange exchange) throws Exception {

		try {
			logger.info("Loading XML Document : ");
			String body = exchange.getIn().getBody(String.class);
			logger.info(body);

			JAXBContext jc = JAXBContext.newInstance(Channels.class);
			Document xml = loadXMLFromString(body);

			Unmarshaller unmarshaller = jc.createUnmarshaller();
			Channels input = (Channels) unmarshaller.unmarshal(xml);

			this.createXSLTAndStore(exchange, input);
			
			exchange.getContext().startAllRoutes();

		} catch (Exception exc) {
			logger.warn(exc.getMessage());
		}
	}

	public void createXSLTAndStore(Exchange exchange, Channels channelRoot) throws Exception {
		// Dev ref: https://xsltfiddle.liberty-development.net/nc4NzQd/31
		
		String xsltPath = (String) exchange.getIn().getHeader("XSLTPath");
		
		List<Channels.Mappings> mappings = channelRoot.getMappings();

		StringBuilder dynamicTemp = new StringBuilder();

		for (int x = 0; x < mappings.size(); x++) {
			Channels.Mappings mapping = mappings.get(x);

			logger.info("Channel Type : " + mapping.getType());

			List<Channels.Mappings.Mapping> mappingList = mapping.getMapping();
			for (int y = 0; y < mappingList.size(); y++) {
				Channels.Mappings.Mapping mappingObj = mappingList.get(y);

				dynamicTemp.append("    <xsl:template match=\"" + mapping.getXpath() + "/text()[.='"
						+ mappingObj.getFind() + "']\">" + mappingObj.getReplaceWith() + "</xsl:template>\r\n");
			}


			String xslt = "<xsl:stylesheet version=\"2.0\" xmlns:xsl='http://www.w3.org/1999/XSL/Transform' \r\n" + 
					"xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" exclude-result-prefixes=\"xs\">\r\n" + 
					"	<xsl:output method=\"xml\" indent=\"yes\" standalone=\"yes\" />\r\n" + 
					"	\r\n" + 
					"	<xsl:template match=\"node()|@*\">\r\n" + 
					"        <xsl:copy>\r\n" + 
					"            <xsl:apply-templates select=\"node()|@*\"/>\r\n" + 
					"        </xsl:copy>\r\n" + 
					"    </xsl:template>\r\n" + 
					"\r\n" + dynamicTemp.toString() + "	\r\n" + 
					"</xsl:stylesheet>";
			
			this.writeToFile(xsltPath + "/" + mapping.getType() + ".xsl", xslt);
		}
		
		this.createRouteDnamic(exchange, channelRoot);
	}
	
	public void createRouteDnamic(Exchange exchange, Channels channelRoot) throws Exception {	
		String xsltPath = (String) exchange.getIn().getHeader("XSLTPath");
		String configFilePath = (String) exchange.getIn().getHeader("CONFIGFilePath");
			
		List<Channels.Channel> channels = channelRoot.getChannel();

		for (int x = 0; x < channels.size(); x++) {
			Channels.Channel channel = channels.get(x);
			logger.info("Channel Type : " + channel.getType());

			Channels.Channel.Credentials credential = channel.getCredentials();
			logger.info("Credentials URL : " + credential.getUrl());

			List<Channels.Channel.From> froms = channel.getFrom();
			if(channel.getType().equals("OUTPUT")) {
				froms = this.getList(credential, froms);
			}
				
			for (int y = 0; y < froms.size(); y++) {
				Channels.Channel.From from = froms.get(y);
				String fromUri = credential.getUrl() + from.getPath() + 
						"?username=" + credential.getUsername() + 
						"&password=" + credential.getPassword() + 
						"&localWorkDirectory=\\tmpATSLinkDemux&delete=true";
				
				logger.info(fromUri);
				RouteBuilder builder = new RouteBuilder() {
					public void configure() {
						errorHandler(deadLetterChannel("mock:error"));

					    from(fromUri)
					    .autoStartup(false)
					    .setHeader("CONFIGFilePath").simple(configFilePath)
					    .setHeader("ChannelType").simple(channel.getType())
					    .process(new DemuxProcessor())
					    .to("xslt:file:///" + xsltPath + "/" + channel.getType() + ".xsl?saxon=true")
					    .toD("${headers.destinationFolder}");
					}
				};
				
				exchange.getContext().addRoutes(builder);
			}
		}
		
		logger.warn("Dynamic routes.");
	}
	
	public void writeToFile(String path, String content) {

        try {
            File newTextFile = new File(path);

            FileWriter fw = new FileWriter(newTextFile);
            fw.write(content);
            fw.close();

        } catch (IOException iox) {
            //do stuff with exception
            iox.printStackTrace();
        }
    }

	public List<Channels.Channel.From> getList(Channels.Channel.Credentials credential, List<Channels.Channel.From> froms) throws SocketException, IOException, URISyntaxException {
		// Dev ref: http://www.codejava.net/java-se/networking/ftp/list-files-and-directories-recursively-on-a-ftp-server
		
		List<Channels.Channel.From> rtnFroms = new ArrayList<>();
		
		FTPClient client = new org.apache.commons.net.ftp.FTPClient();
		
		URI uri = new URI(credential.getUrl());		
		client.connect(uri.getHost(),uri.getPort());
		client.login(credential.getUsername(), credential.getPassword());
		
		FTPFile[] names = client.listDirectories();

		for (int x = 0; x < froms.size(); x++) {
			String fromPath = froms.get(x).getPath();
			
			for (int y = 0; y < names.length; y++) {
				
				if(names[y].getName().endsWith(fromPath)) {
					logger.info(names[y].getName());
					Channels.Channel.From newFrom = new Channels.Channel.From();
					newFrom.setPath(names[y].getName());
					rtnFroms.add(newFrom);
					logger.warn(newFrom.getPath());
				}
			}
		}
		
		return rtnFroms;
	}

	public Document loadXMLFromString(String xml) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		byte[] byt = xml.getBytes();
		ByteArrayInputStream bytAryIptStrm = new ByteArrayInputStream(byt);
		return builder.parse(bytAryIptStrm);
	}
	
}
