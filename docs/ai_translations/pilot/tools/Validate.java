import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.*;
import java.io.File;
public class Validate {
    public static void main(String[] a) throws Exception {
        SchemaFactory f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema s = f.newSchema(new File(a[0]));
        for (int i = 1; i < a.length; i++) {
            try { s.newValidator().validate(new StreamSource(new File(a[i]))); System.out.println(a[i] + " => VALID"); }
            catch (Exception e) { System.out.println(a[i] + " => INVALID: " + e.getMessage()); }
        }
    }
}
