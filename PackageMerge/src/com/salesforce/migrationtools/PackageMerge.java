package com.salesforce.migrationtools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

import com.salesforce.migrationtoolutils.Utils;


public class PackageMerge {
	
	private static String workingDirPath = "./workingDir";
	private static String targetDirName = "target";

	public static void main(String[] args) throws IOException, ParserConfigurationException, SAXException, TransformerException {
		
		if (args.length < 2) {
			System.out.println("Usage: java -jar PackageMerge.jar outputfilename inputfile1 [inputfile2] [inputfile3] ...");
			System.out.println("Will merge any input files provided into outputfilename, retaining inputfile1's version setting.");
			System.exit(0);
		}
		
		File outputFile = new File(args[0]);

		processFiles(outputFile, args);

	}
	
	public static void processFiles(File outputFile, String[] args) {
		String targetFilename = args[0];
		String firstFilename = args[1];
		String targetDirPath = Utils.checkPathSlash(workingDirPath) + targetDirName;
		String tempDirPath = Utils.checkPathSlash(workingDirPath) + "temp"; 
		
		try {
			
			// clear out & create working dir
			Utils.purgeDirectory(new File(workingDirPath));
			Utils.checkDir(workingDirPath);
			
			// create target dir
			
			Utils.checkDir(targetDirPath);
			
			
			
			if (Utils.checkIfFileExists(firstFilename) && !Utils.checkIsDirectory(firstFilename)) {
				// unzip first file into target dir - if it's a file
				Utils.unzip(firstFilename, targetDirPath);
			} else {
				// just copy it
				Utils.copyDirContent(firstFilename, targetDirPath);
			}

			// for each file
			
			for (int i = 2; i < args.length; i++) {
				
				// create temp dir for file in workdir
				
				Utils.checkDir(tempDirPath);
				
				// unzip into temp dir
				
				if (Utils.checkIfFileExists(args[i]) && !Utils.checkIsDirectory(args[i])) {
					// unzip file into target dir - if it's a file
					Utils.unzip(args[i], tempDirPath);
				} else {
					// just copy it
					Utils.copyDirContent(args[i], tempDirPath);
				}
				
				// get package.xml from current target & temp dir
				
				String targetPackageXMLPath = Utils.checkPathSlash(targetDirPath) + "package.xml";
				String toBeMergedPackageXMLPath = Utils.checkPathSlash(tempDirPath) + "package.xml";
				
				// merge package.xmls
				// this leaves the target package.xml as the sum total of the two, so the merged package.xml is safe to remove
				
				ArrayList<String> collisionList = checkFileCollisions(targetDirPath, tempDirPath);
				
				if (!collisionList.isEmpty()) {
					System.out.println("Package " + args[i] + " cannot be merged on top of what is already done, there are file-level collisions:");
					for (String s : collisionList) {
						System.out.println(s);
					}
					System.exit(-1);
				}
				
				mergePackageXMLs(targetPackageXMLPath, toBeMergedPackageXMLPath);
				new File(toBeMergedPackageXMLPath).delete();
				
				// merge file content into target dir
			
				Utils.mergeTwoDirectories(new File(targetDirPath), new File(tempDirPath));
				System.out.println("Merged package: " + args[i]);
			}
			
			// zip up the target dir
			
			Utils.zipIt(targetFilename, targetDirPath);
			
			// remove working dir
			
			Utils.purgeDirectory(new File(workingDirPath));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ParserConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SAXException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (TransformerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		
		
	}

	private static ArrayList<String> checkFileCollisions(String targetPackagePath, String toBeMergedPackagePath) {
		ArrayList<String> collisionList = new ArrayList<String>();
		Iterator<File> filesIterator = FileUtils.iterateFiles(new File(toBeMergedPackagePath), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
		
		
		String currentDir = "";
		while (filesIterator.hasNext()) {
			File toBeMergedFile = filesIterator.next();
			String actualFilePath = toBeMergedFile.getPath().replace(toBeMergedPackagePath, "");
			if (toBeMergedFile.getName().startsWith(".") || toBeMergedFile.getName().equals("package.xml")) {
				continue;
			}
			if (toBeMergedFile.isDirectory()) {
				currentDir = toBeMergedFile.getAbsolutePath().replace(toBeMergedPackagePath, "");
			} else {
				if (Utils.checkIfFileExists(targetPackagePath + actualFilePath)) {
					collisionList.add(currentDir + toBeMergedFile.getName());
				}
			}
			
		}
		
		return collisionList;
	}

	public static void mergePackageXMLs(String targetPackageXMLPath, String toBeMergedPackageXMLPath) throws IOException, ParserConfigurationException, SAXException, TransformerException {

		
		
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		
		ArrayList<HashMap<String, HashSet<String>>> maps = new ArrayList<HashMap<String, HashSet<String>>>();
		Document doc1 = null;
		boolean firstPass = true;
		for (String fileName : new String[]{targetPackageXMLPath, toBeMergedPackageXMLPath}) {
			File fileToParse = new File(fileName);
			
			if (fileToParse != null && fileToParse.exists()) {
				Document doc;
				try {
					doc = dBuilder.parse(fileToParse);
					maps.add(convertXmlToMetadataHash(doc));
					if (firstPass) {
						doc1=doc;
						firstPass = false;
					}
				} catch (Exception e) {
					System.out.println("Error parsing file " + fileName + ", skipping...");
					e.printStackTrace();
				}
				
			} else {
				System.out.println("File " + fileName + " not found or can't be opened, skipping...");
			}
		}
		
		// join the collections

		HashMap<String, HashSet<String>> resultPackageHash = joinHashmaps(maps);

		// produce the output document

		Element packageElement = doc1.getDocumentElement();
		Element versionElement = (Element) packageElement.getElementsByTagName("version").item(0);

		Document output = dBuilder.newDocument();
		Node rootNode = output.importNode(doc1.getDocumentElement(), false);
		output.appendChild(rootNode);
		Element outputPackageElement = output.getDocumentElement();

		// add version element
		Node versionNode = output.importNode(versionElement, true);
		Element outputVersionElement = (Element) versionNode;
		outputPackageElement.appendChild(outputVersionElement);

		for (String mdTypeName : new ArrayList<String>(new TreeSet<String>(resultPackageHash.keySet()))) {
			// create types element

			Element typesElement = output.createElement("types");
			outputPackageElement.appendChild(typesElement);
			Element nameElement = output.createElement("name");
			nameElement.setTextContent(mdTypeName);
			typesElement.appendChild(nameElement);

			HashSet<String> itemsList = resultPackageHash.get(mdTypeName);
			for (String itemName : new ArrayList<String>(new TreeSet<String>(itemsList))) {
				// create element
				Element membersElement = output.createElement("members");
				membersElement.setTextContent(itemName);
				typesElement.appendChild(membersElement);
			}

		}

		// write the content into xml file
		TransformerFactory transformerFactory = TransformerFactory.newInstance();
		Transformer transformer = transformerFactory.newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
		DOMSource source = new DOMSource(output);
		StreamResult result = new StreamResult(targetPackageXMLPath);

		// Output to console for testing
		// StreamResult result = new StreamResult(System.out);

		transformer.transform(source, result);

	}

	private static HashMap<String, HashSet<String>> convertXmlToMetadataHash(Document xml) {

		HashMap<String, HashSet<String>> retval = new HashMap<String, HashSet<String>>();

		NodeList typesList = xml.getElementsByTagName("types");

		for (int i = 0; i < typesList.getLength(); i++) {

			Element mdTypeElement = (Element) typesList.item(i);
			String mdTypeName = mdTypeElement.getElementsByTagName("name").item(0).getTextContent();

			//System.out.println("Processing metadata type: " + mdTypeName);

			HashSet<String> itemSet = null;

			if (retval.get(mdTypeName) != null) {
				itemSet = retval.get(mdTypeName);
			}

			if (itemSet == null) {
				itemSet = new HashSet<String>();
				retval.put(mdTypeName, itemSet);
			}

			NodeList memberList = mdTypeElement.getElementsByTagName("members");

			for (int j = 0; j < memberList.getLength(); j++) {
				Element memberElement = (Element) memberList.item(j);
				itemSet.add(memberElement.getTextContent());
				//System.out.println("Found item: " + memberElement.getTextContent());
			}
		}

		return retval;

	}

	private static HashMap<String, HashSet<String>> joinHashmaps(ArrayList<HashMap<String, HashSet<String>>> maps) {
		HashMap<String, HashSet<String>> retval = new HashMap<String, HashSet<String>>();

		for (HashMap<String, HashSet<String>> map : maps) {
			for (String mdType : map.keySet()) {
				HashSet<String> targetItemSet = getItemSet(retval, mdType);
				HashSet<String> sourceItemSet = getItemSet(map, mdType);
				targetItemSet.addAll(sourceItemSet);
			}
		}

		return retval;
	}

	private static HashSet<String> getItemSet(HashMap<String, HashSet<String>> map, String typeName) {
		HashSet<String> itemSet = null;

		if (map.get(typeName) != null) {
			itemSet = map.get(typeName);
		} else {
			itemSet = new HashSet<String>();
			map.put(typeName, itemSet);
		}
		return itemSet;
	}
}
