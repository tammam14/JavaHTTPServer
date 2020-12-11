package com.mycompany.serverjavahttp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;

public class JavaHTTPServer implements Runnable
{	
	static final File WEB_ROOT = new File("./files/");
	static final String DEFAULT_FILE = "index.html";
	static final String FILE_NOT_FOUND = "404.html";
	static final String METHOD_NOT_SUPPORTED = "not_supported.html";
    
    static final String DB_JSON_URL = "/db/json/";
    static final String DB_XML_URL = "/db/xml/";
	
    static final int PORT = 8080;
	
	static final boolean verbose = true;
	
	private Socket socket;
	
	public JavaHTTPServer(Socket s) 
    {
		socket = s;
	}
	
	public static void main(String[] args) 
    {
		try 
        {
            String DRIVER = "com.mysql.cj.jdbc.Driver";
            Class.forName(DRIVER);
            
			ServerSocket serverConnect = new ServerSocket(PORT);
			System.out.println("Server started.\nListening for connections on port : " + PORT + " ...\n");
			
			//infinite loop to accept connections
			while (true) 
            {
				JavaHTTPServer myServer = new JavaHTTPServer(serverConnect.accept());
				
				if (verbose) 
					System.out.println("Connecton opened. (" + new Date() + ")");
				
				//create dedicated thread to manage the client connection
				Thread thread = new Thread(myServer);
				thread.start();
			}
		} 
        catch (IOException e) 
        {
			System.err.println("Server Connection error : " + e.getMessage());
		}
        catch (ClassNotFoundException e)
        {
            System.out.println("JDBC driver not found...");
            System.exit(1);
        }
	}

	@Override
	public void run() 
    {
		//this is the activity of a thread  
        
        //readers and writers
		BufferedReader in = null;
        PrintWriter headerOut = null;
        BufferedOutputStream dataOut = null;
        
		String filePath = null;
        List<String> supportedMethods = new ArrayList<>(Arrays.asList("GET", "HEAD"));
		
		try 
        {
            //input stream
			in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			//text output stream for headers
			headerOut = new PrintWriter(socket.getOutputStream());
			//binary output stream for data
			dataOut = new BufferedOutputStream(socket.getOutputStream());
			
			//get first line of the request from the client
			String input = in.readLine();
			//parse the request with a string tokenizer
			StringTokenizer parser = new StringTokenizer(input);
			//get method and file requested
			String method = parser.nextToken().toUpperCase();
			filePath = parser.nextToken().toLowerCase();
			
			//check supported methods
			if(!supportedMethods.contains(method)) 
            {
				if (verbose) 
					System.out.println("501 Not Implemented : " + method + " method.");
				
				//return the not supported file to the client
				File file = new File(WEB_ROOT, METHOD_NOT_SUPPORTED);
				int fileLength = (int)file.length();
				String contentMimeType = "text/html";
				byte[] fileData = readFileData(file, fileLength);
					
				//send HTTP headers
				headerOut.println("HTTP/1.1 501 Not Implemented");
				headerOut.println("Server: Java HTTP Server from samu902 : 1.0");
				headerOut.println("Date: " + new Date());
				headerOut.println("Content-type: " + contentMimeType);
				headerOut.println("Content-length: " + fileLength);
				headerOut.println(); //blank line between headers and content, very important!
				headerOut.flush(); 
				//send file data
				dataOut.write(fileData, 0, fileLength);
				dataOut.flush();	
			} 
            else 
            {
                //special treatment for this file
                if(filePath.endsWith("/punti-vendita.xml"))
                {
                    puntiVendita(headerOut, dataOut);
                    return;
                }
                
                //DB paths
                if(filePath.equals(DB_JSON_URL) || filePath.equals(DB_XML_URL))
                {
                    databaseData(headerOut, dataOut, filePath);
                    return;
                }
                
				//if path is a folder, append the default file
				if (filePath.endsWith("/"))
					filePath += DEFAULT_FILE;
				
				File file = new File(WEB_ROOT, filePath);
				int fileLength = (int) file.length();
				String content = getContentType(filePath);
				
                //GET method so we return content
				if (method.equals("GET")) 
                {
					byte[] fileData = readFileData(file, fileLength);
					
					//send HTTP headers
					headerOut.println("HTTP/1.1 200 OK");
					headerOut.println("Server: Java HTTP Server from Samu902 : 1.0");
					headerOut.println("Date: " + new Date());
					headerOut.println("Content-type: " + content);
					headerOut.println("Content-length: " + fileLength);
					headerOut.println(); // blank line between headers and content, very important!
					headerOut.flush();
                    //send file data
                    dataOut.write(fileData, 0, fileLength);
                    dataOut.flush();
				}
				
				if (verbose)
					System.out.println("File " + filePath + " of type " + content + " returned");	
			}
		} 
        catch (FileNotFoundException fnfe) 
        {
			try 
            {                
                //if file is without extension (and not a folder) and doesn't exist, try to redirect to a folder with the same name
                if(getFileExtension(filePath).equals("") && !filePath.endsWith("/"))
                {
                    redirectToFolder(headerOut, filePath);
                    return;
                }
                
				fileNotFound(headerOut, dataOut, filePath);
			} 
            catch (IOException ioe) 
            {
				System.err.println("Error with file not found exception : " + ioe.getMessage());
			}
		}
        catch (IOException ioe) 
        {
			System.err.println("Server error : " + ioe);
		} 
        finally 
        {
			try 
            {
                //close streams and socket connection
				in.close();
				headerOut.close();
				dataOut.close();
				socket.close();
			} 
            catch (Exception e) 
            {
				System.err.println("Error closing stream : " + e.getMessage());
			} 
			
			if (verbose)
				System.out.println("Connection closed.\n");
		}
	}
	
    //reads binary file data
	private byte[] readFileData(File file, int fileLength) throws IOException 
    {
		FileInputStream fileIn = null;
		byte[] fileData = new byte[fileLength];
		
		try 
        {
			fileIn = new FileInputStream(file);
			fileIn.read(fileData);
		} 
        finally 
        {
			if (fileIn != null) 
				fileIn.close();
		}
		
		return fileData;
	}
	
	//return supported MIME Types
	private String getContentType(String filePath) 
    {
		if (filePath.endsWith(".htm")  ||  filePath.endsWith(".html"))
			return "text/html";
		else
			return "text/plain";
	}
    
    private String getFileExtension(String filePath)
    {
        String extension = "";
        int index = filePath.lastIndexOf('.');
        
        if(index > 0)
            extension = filePath.substring(index + 1);
                
        return extension;
    }
	
    //sends a 404 response
	private void fileNotFound(PrintWriter headerOut, OutputStream dataOut, String filePath) throws IOException 
    {
		File file = new File(WEB_ROOT, FILE_NOT_FOUND);
		int fileLength = (int)file.length();
		String content = "text/html";
		byte[] fileData = readFileData(file, fileLength);
		
		headerOut.println("HTTP/1.1 404 File Not Found");
		headerOut.println("Server: Java HTTP Server from Samu902 : 1.0");
		headerOut.println("Date: " + new Date());
		headerOut.println("Content-type: " + content);
		headerOut.println("Content-length: " + fileLength);
		headerOut.println(); // blank line between headers and content, very important!
		headerOut.flush();
		
		dataOut.write(fileData, 0, fileLength);
		dataOut.flush();
		
		if (verbose)
			System.out.println("File " + filePath + " not found");
	}
    
    //sends a 301 redirect to a folder named like the file passed in
    private void redirectToFolder(PrintWriter headerOut, String filePath) throws IOException 
    {
        //send HTTP Headers
        headerOut.println("HTTP/1.1 301 Moved Permanently");
        headerOut.println("Server: Java HTTP Server from Samu902 : 1.0");
        headerOut.println("Location: " + filePath + "/");
        headerOut.println(); // blank line between headers and content, very important!
        headerOut.flush();
    }
    
    //returns to client an xml from a json file
    private void puntiVendita(PrintWriter headerOut, BufferedOutputStream dataOut) throws IOException 
    {
        //mappers
        JsonMapper jsonMapper = new JsonMapper();
        XmlMapper xmlMapper = new XmlMapper();
        
        //parse json data into array, then serialize it in xml
        ArrayList<PuntoVendita> puntiVendita = jsonMapper.readValue(new File(WEB_ROOT + "/myfolder/punti-vendita.json"), new TypeReference<ArrayList<PuntoVendita>>(){});
        String xmlData = xmlMapper.writeValueAsString(puntiVendita);
        byte[] byteData = xmlData.getBytes();
                
        //send HTTP headers
        headerOut.println("HTTP/1.1 200 OK");
        headerOut.println("Server: Java HTTP Server from Samu902 : 1.0");
        headerOut.println("Date: " + new Date());
        headerOut.println("Content-type: application/xml");
        headerOut.println("Content-length: " + xmlData.length());
        headerOut.println(); // blank line between headers and content, very important!
        headerOut.flush();
        //send file data
        dataOut.write(byteData, 0, xmlData.length());
        dataOut.flush();
    }
    
    private void databaseData(PrintWriter headerOut, BufferedOutputStream dataOut, String filePath) throws IOException
    {
        ArrayList<Persona> persone = new ArrayList<>();
        String URL_DB = "jdbc:mysql://localhost:3306/javadb";
        Connection conn = null;
        try
        {
            //establish connection with DB
            conn = DriverManager.getConnection(URL_DB, "root", "root");
            Statement stat = conn.createStatement();
            ResultSet resultSet = stat.executeQuery("SELECT * FROM persona");

            //read data from DB
            while (resultSet.next())
            {
                int id = resultSet.getInt("id");
                String nome = resultSet.getString("nome");
                String cognome  = resultSet.getString("cognome");
                String telefono = resultSet.getString("telefono");
                
                persone.add(new Persona(id, nome, cognome, telefono));
            }
        } 
        catch (Exception e)
        {
            System.out.println("Error during DB connection " + e);
            System.exit(1);
        }
        finally 
        {
            //close DB connection
            if(conn != null)
            {
                try
                {
                    conn.close();
                } 
                catch (Exception e)
                {
                    System.out.println("Error closing DB connection");
                }
            }
        }
        
        //format data into json or xml
        String contentType = "";
        String textData = "";
        if(filePath.equals(DB_JSON_URL))
        {
            JsonMapper jsonMapper = new JsonMapper();
            contentType = "application/json";
            textData = jsonMapper.writeValueAsString(persone);
        }
        else
        {
            XmlMapper xmlMapper = new XmlMapper();
            contentType = "application/xml";
            textData = xmlMapper.writeValueAsString(persone);
        }
        byte[] byteData = textData.getBytes();
  
        //send HTTP headers
        headerOut.println("HTTP/1.1 200 OK");
        headerOut.println("Server: Java HTTP Server from Samu902 : 1.0");
        headerOut.println("Date: " + new Date());
        headerOut.println("Content-type: " + contentType);
        headerOut.println("Content-length: " + textData.length());
        headerOut.println(); // blank line between headers and content, very important!
        headerOut.flush();
        //send file data
        dataOut.write(byteData, 0, textData.length());
        dataOut.flush();
    }
}