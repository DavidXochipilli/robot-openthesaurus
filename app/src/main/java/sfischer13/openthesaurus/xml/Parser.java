/*
   Copyright 2015-2017 Stefan Fischer

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package sfischer13.openthesaurus.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import sfischer13.openthesaurus.model.Synset;
import sfischer13.openthesaurus.model.Term;
import sfischer13.openthesaurus.util.Net;

public class Parser {
    private static final int CONNECT_TIMEOUT = 3000;
    private static final int READ_TIMEOUT = 6000;
    private static final String QUERY_URL = "https://www.openthesaurus.de/synonyme/search?format=text/xml&mode=all&q=";
    private static final String SUPPORTED_API = "0.1.3";

    private Parser() {
    }

    public static Result query(String query) {
        URL url = getQueryUrl(query);
        HttpsURLConnection connection = getConnection(url);
        String response = getResponse(connection);
        return parseResponse(response);
    }

    private static URL getQueryUrl(String query) {
        String encoded = Net.encodeUrl(query);
        if (encoded == null) {
            return null;
        }

        try {
            return new URL(QUERY_URL + encoded);
        } catch (MalformedURLException mue) {
            return null;
        }
    }

    private static HttpsURLConnection getConnection(URL url) {
        if (url == null) {
            return null;
        }

        boolean error = false;
        HttpsURLConnection connection;
        try {
            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);

            // redirection?
            if (!url.getHost().equals(connection.getURL().getHost())) {
                error = true;
            }
        } catch (IOException ioe) {
            return null;
        }

        // answer OK?
        try {
            if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                error = true;
            }
        } catch (IOException ioe) {
            error = true;
        }

        if (error) {
            connection.disconnect();
            return null;
        }

        return connection;
    }

    private static String getResponse(HttpsURLConnection connection) {
        if (connection == null) {
            return null;
        }

        String result;
        StringBuilder sb = new StringBuilder();
        InputStream is;
        try {
            is = new BufferedInputStream(connection.getInputStream());
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line;
            try {
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                result = sb.toString();
            } catch (IOException ioe) {
                result = null;
            } finally {
                br.close();
            }
        } catch (IOException ioe) {
            result = null;
        }

        connection.disconnect();
        return result;
    }

    private static Result parseResponse(String response) {
        if (response == null) {
            return null;
        }

        Result result;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            InputSource is = new InputSource(new StringReader(response));
            try {
                result = parseDocument(builder.parse(is));
            } catch (SAXException saxe) {
                result = null;
            } catch (IOException ioe) {
                result = null;
            }
        } catch (ParserConfigurationException pce) {
            result = null;
        }
        return result;
    }

    private static Result parseDocument(Document doc) {
        if (!isApiSupported(doc)) {
            return null;
        }

        List<Synset> matches = parseMatches(doc);
        if (matches == null) {
            return null;
        }

        List<SuggestionCollection> suggestions = parseSuggestions(doc);
        if (suggestions == null) {
            return null;
        }

        return new Result(matches, suggestions);
    }

    private static boolean isApiSupported(Document doc) {
        boolean result;
        try {
            Node versionNode = doc.getElementsByTagName("apiVersion").item(0);
            String versionString = versionNode.getAttributes().getNamedItem("content").getNodeValue();
            result = SUPPORTED_API.equals(versionString);
        } catch (NullPointerException npe) {
            result = false;
        }
        return result;
    }

    private static List<Synset> parseMatches(Document doc) {
        List<Synset> result;
        XPath xpath = XPathFactory.newInstance().newXPath();
        try {
            XPathExpression expression = xpath.compile("//matches/synset");
            NodeList nodes = (NodeList) expression.evaluate(doc, XPathConstants.NODESET);
            result = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Synset synset = new Synset();
                NodeList children = nodes.item(i).getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    String name = child.getNodeName();
                    if (name.equals("term")) {
                        String termAttribute = child.getAttributes().getNamedItem("term").getNodeValue();
                        Node levelNode = child.getAttributes().getNamedItem("level");
                        if (levelNode == null) {
                            synset.add(new Term(termAttribute));
                        } else {
                            synset.add(new Term(termAttribute, levelNode.getNodeValue()));
                        }
                    }
                }
                result.add(synset);
            }
        } catch (XPathExpressionException xpe) {
            result = null;
        }
        return result;
    }

    private static List<SuggestionCollection> parseSuggestions(Document doc) {
        List<SuggestionCollection> suggestions = new ArrayList<>();
        for (SuggestionCollection.Type type : SuggestionCollection.Type.values()) {
            SuggestionCollection collection = parseSuggestionCollection(doc, type);
            if (collection == null) {
                return null;
            } else if (collection.getTerms().size() != 0) {
                suggestions.add(collection);
            }
        }
        return suggestions;
    }

    private static SuggestionCollection parseSuggestionCollection(Document doc, SuggestionCollection.Type type) {
        SuggestionCollection collection;
        XPath xpath = XPathFactory.newInstance().newXPath();
        try {
            XPathExpression expression = xpath.compile(type.path());
            NodeList nodes = (NodeList) expression.evaluate(doc, XPathConstants.NODESET);
            collection = new SuggestionCollection(type);
            for (int i = 0; i < nodes.getLength(); i++) {
                Node child = nodes.item(i);
                String name = child.getNodeName();
                if (name.equals("term")) {
                    String termAttribute = child.getAttributes().getNamedItem("term").getNodeValue();
                    collection.add(new Term(termAttribute));
                }
            }
        } catch (XPathExpressionException xpe) {
            collection = null;
        }
        return collection;
    }
}