package audit;

import com.sun.source.util.JavacTask;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Parses all project Java without linking Android SDK types or evaluating source code. */
public final class JavaSyntaxParse {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected Android project root");
        File source = new File(args[0], "app/src");
        List<File> javaFiles = new ArrayList<>();
        visit(source, javaFiles);
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK javac compiler is required");
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            Iterable<? extends JavaFileObject> sources=manager.getJavaFileObjectsFromFiles(javaFiles);
            JavacTask parser=(JavacTask) compiler.getTask(null,manager,diagnostics,
                    Arrays.asList("-proc:none","-encoding","UTF-8","--release","17"),null,sources);
            int syntaxTrees=0;
            for(Object tree:parser.parse())syntaxTrees++;
            int errors=0;
            for(Diagnostic<? extends JavaFileObject> d:diagnostics.getDiagnostics()) {
                if(d.getKind()==Diagnostic.Kind.ERROR) {
                    errors++;
                    System.err.println(d.getSource()+":"+d.getLineNumber()+":"+d.getColumnNumber()+" "+d.getMessage(null));
                }
            }
            if(errors>0 || syntaxTrees!=javaFiles.size()) {
                throw new AssertionError("Java syntax parse failed: errors="+errors
                        +", files="+javaFiles.size()+", syntaxTrees="+syntaxTrees);
            }
            System.out.println("JAVA_PROJECT_PARSE_PASS parsed_java_files="+javaFiles.size());
        }
    }
    private static void visit(File item,List<File> output) {
        if(item.isDirectory()) {
            File[] entries=item.listFiles();
            if(entries!=null)for(File f:entries)visit(f,output);
        } else if(item.getName().endsWith(".java")) output.add(item);
    }
}
