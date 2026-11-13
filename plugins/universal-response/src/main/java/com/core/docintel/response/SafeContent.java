package com.core.docintel.response;
import java.io.*;
import java.net.URI;
import java.util.*;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import javax.xml.parsers.*;
import javax.xml.XMLConstants;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

final class SafeContent {
    private SafeContent(){}
    static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    static String html(String raw){
        Set<String> tags=Set.of("p","div","span","h1","h2","h3","h4","h5","h6","strong","b","em","i","u","s","pre","code","blockquote","ul","ol","li","table","thead","tbody","tfoot","tr","td","th","br","hr","details","summary");
        StringBuilder out=new StringBuilder();
        try{new ParserDelegator().parse(new StringReader(raw),new HTMLEditorKit.ParserCallback(){
            int blocked,elements;
            boolean blocked(String tag){return Set.of("script","style","iframe","object","embed","template","svg","math").contains(tag);}
            public void handleStartTag(HTML.Tag t,MutableAttributeSet a,int p){if(++elements>2000)throw new IllegalArgumentException("HTML element limit exceeded.");String tag=t.toString();if(blocked(tag)){blocked++;return;}if(blocked>0||!tags.contains(tag))return;out.append('<').append(tag).append('>');}
            public void handleEndTag(HTML.Tag t,int p){String tag=t.toString();if(blocked(tag)){blocked=Math.max(0,blocked-1);return;}if(blocked==0&&tags.contains(tag))out.append("</").append(tag).append('>');}
            public void handleSimpleTag(HTML.Tag t,MutableAttributeSet a,int p){if(blocked==0&&tags.contains(t.toString()))out.append('<').append(t).append('>');}
            public void handleText(char[] text,int p){if(blocked==0)out.append(escape(new String(text)));}
        },true);}catch(IOException e){throw new IllegalArgumentException("HTML could not be sanitized.");}return out.toString();
    }
    static String svg(String raw){
        try{
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());Document document=builder.parse(new InputSource(new StringReader(raw)));Element root=document.getDocumentElement();if(!root.getLocalName().equals("svg"))throw new IllegalArgumentException();
            StringBuilder out=new StringBuilder();svgElement(root,out,0,new int[]{5000});return out.toString();
        }catch(Exception e){throw new IllegalArgumentException("Invalid or unsafe SVG.");}
    }
    private static void svgElement(Element element,StringBuilder out,int depth,int[] budget){
        if(depth>30||--budget[0]<0)throw new IllegalArgumentException("SVG element or nesting limit exceeded.");
        Set<String> tags=Set.of("svg","g","path","rect","circle","ellipse","line","polyline","polygon","text","tspan","defs","marker","linearGradient","radialGradient","stop","title","desc");String tag=element.getLocalName();
        if(!tags.contains(tag)||element.getNamespaceURI()!=null&&!element.getNamespaceURI().equals("http://www.w3.org/2000/svg"))return;
        Set<String> attributes=Set.of("id","viewBox","width","height","x","y","x1","x2","y1","y2","cx","cy","r","rx","ry","d","points","fill","stroke","stroke-width","stroke-linecap","stroke-linejoin","opacity","fill-opacity","stroke-opacity","transform","font-size","text-anchor","dx","dy","marker-end","marker-start","markerWidth","markerHeight","refX","refY","orient","offset","stop-color","stop-opacity");
        out.append('<').append(tag);if(tag.equals("svg"))out.append(" xmlns=\"http://www.w3.org/2000/svg\"");
        var attrs=element.getAttributes();for(int i=0;i<attrs.getLength();i++){var a=attrs.item(i);String value=a.getNodeValue();if(!attributes.contains(a.getNodeName())||value.length()>100000)continue;if(value.toLowerCase(Locale.ROOT).contains("url(")&&!value.matches("url\\(#[A-Za-z0-9_-]+\\)"))continue;out.append(' ').append(a.getNodeName()).append("=\"").append(escape(value)).append('"');}
        out.append('>');var children=element.getChildNodes();for(int i=0;i<children.getLength();i++){var child=children.item(i);if(child instanceof Element e)svgElement(e,out,depth+1,budget);else if(child.getNodeType()==Node.TEXT_NODE)out.append(escape(child.getNodeValue()));}out.append("</").append(tag).append('>');
    }
    static boolean mediaUrl(String raw){
        try{URI u=URI.create(raw);String host=Objects.toString(u.getHost(),"").toLowerCase(Locale.ROOT);return raw.length()<2048&&u.getScheme().equals("https")&&!host.isEmpty()&&u.getUserInfo()==null&&!host.equals("localhost")&&!host.endsWith(".localhost")&&!host.contains(":")&&!host.matches("[0-9.]+")&&!host.endsWith(".local");}catch(Exception e){return false;}
    }
}
