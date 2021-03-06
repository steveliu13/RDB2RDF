import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Selector;
import org.apache.jena.rdf.model.SimpleSelector;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import java.io.*;
import java.sql.SQLException;
import java.util.*;

/**
 * Created by The Illsionist on 2017/11/10.
 */
public class TransformThread implements Runnable{

    private String dbName = null;  //数据库名
    private ModelTransformer modelTransformer = null;  //转换器
    private Model model = null;

    public TransformThread(String dbName){
        this.dbName = dbName;
        modelTransformer = new ModelTransformer(dbName);
    }

    @Override
    public void run() {
        if(dbName == null)
            return;
        //读取模型转换所必须的人工配置信息
        Thread subReadThread = new Thread(new Runnable() {
            @Override
            public void run() {
                modelTransformer.setEntityTableMap(getEntityTableMap());
                modelTransformer.setPyToZh(getPyToZh());
                modelTransformer.setColJudgeDic(getColJudgeDic());
                modelTransformer.setRelTbToRelMap(getRelTbToRelMap());
                modelTransformer.setEntityDescMap(getEntityDescMap());
            }
        });
        System.out.println("正读取数据库" + dbName + "的转换配置信息......");
        subReadThread.start();
        try {
            subReadThread.join();
            System.out.println("数据库" + dbName + "的转换配置信息读取成功!");
        } catch (InterruptedException e) {
            System.out.println("执行读取数据库" + dbName + "转换配置信息子线程时出现错误!");
            e.printStackTrace();
            return;
        }
        System.out.println("现在开始对RDB模型进行转换......");
        Thread subWriteThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try{
                    modelTransform();
                    System.out.println("转换成功,正在写入文件......");
                    getSchemaModel();
                    File file = new File("./src/main/resources/KG/TestDB.owl");
                    FileOutputStream outputStream = new FileOutputStream(file,false);
                    RDFDataMgr.write(outputStream,model,Lang.TURTLE);
                    outputStream.flush();
                }catch (SQLException e){
                    System.out.println("执行数据库查询出错!");
                    e.printStackTrace();
                    return;
                }catch (FileNotFoundException e){
                    System.out.println("找不到要写入的文件!");
                    e.printStackTrace();
                    return;
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }
        });
        subWriteThread.start();
        try{
            subWriteThread.join();
            System.out.println("RDF写入文件成功!");
            return;
        }catch (InterruptedException e){
            e.printStackTrace();
        }
    }


    public void getSchemaModel() throws SQLException,IOException{
        if(model == null){
            modelTransform();
        }
        Model schema = ModelFactory.createDefaultModel();
        Selector classSelector = new SimpleSelector(null, RDF.type, OWL.Class);
        Selector dataTypeSelector = new SimpleSelector(null,RDF.type, OWL.DatatypeProperty);
        Selector objectSelector = new SimpleSelector(null,RDF.type,OWL.ObjectProperty);
        schema.add(model.listStatements(classSelector));
        schema.add(model.listStatements(dataTypeSelector));
        schema.add(model.listStatements(objectSelector));
        File file = new File("./src/main/resources/KG/TestDBSchema.owl");
        FileOutputStream outputStream = new FileOutputStream(file,false);
        RDFDataMgr.write(outputStream,schema,Lang.TURTLE);
        outputStream.flush();
    }

    /**
     * 模型转换,模型转换要求模式层在先,实例层在后,顺序不能改变
     * @throws SQLException
     */
    private void modelTransform() throws SQLException {
        if (model != null) {
            return;
        } else {     //转换过程
            modelTransformer.transEntitySchema();
            modelTransformer.transEntityInstance();
            modelTransformer.transRelationSchema();
            modelTransformer.transRelationInstance();
            model = modelTransformer.getModel();
        }
        //转换之后的检验
        if (model == null || model.size() == 0) {
            System.out.println("模型转换出现问题,转换结果为0!");
            System.exit(-1);    //转换出现问题时退出程序
        }
    }

    /**
     * 读取中英文映射词典文件
     * 目前文件路径是写死在程序里面的,后期如果有机会可以考虑修改成配置文件等等
     */
    private HashMap<String,String> getPyToZh(){
        BufferedReader reader = null;
        HashMap<String,String> pyToZh = null;
        try {
            reader = new BufferedReader(new FileReader("./src/main/resources/" + dbName + "/pyToZh.txt"));
            pyToZh = new HashMap<>();
            String line = reader.readLine();
            while(line != null && !line.equals("")){
                String[] lineArray = line.trim().split("\\s+");
                pyToZh.put(lineArray[0],lineArray[1]);
                line = reader.readLine();
            }
        } catch (FileNotFoundException e) {
            System.out.println("找不到数据库" + dbName + "的pyToZh.txt文件");
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            System.out.println("在读取数据库" + dbName + "的pyToZh.txt文件时发生错误!");
            e.printStackTrace();
            return null;
        }
        return pyToZh;
    }

    /**
     * 读取字段分类字典
     * 目前文件路径是写死在程序里面的,后期如果有机会可以考虑修改成配置文件等等
     */
    private HashMap<String,PropertyType> getColJudgeDic(){
        BufferedReader reader = null;
        HashMap<String,PropertyType> colJudgeDic = null;
        try{
            reader = new BufferedReader(new FileReader("./src/main/resources/" + dbName + "/colJudgeDic.txt"));
            colJudgeDic = new HashMap<>();
            String line = reader.readLine();
            while(line != null && !line.equals("")){
                String[] lineArray = line.trim().split("\\s+");
				PropertyType type = null;
				switch(lineArray[1]){
				    case "OP":type = PropertyType.OP;break;
					case "GC":type = PropertyType.GC;break;
					case "GP":type = PropertyType.GP;break;
					default:type = PropertyType.DP;break;
				}
                colJudgeDic.put(lineArray[0],type);
                line = reader.readLine();
            }
        } catch (FileNotFoundException e) {
            System.out.println("找不到数据库" + dbName + "的colJudgeDic.txt文件");
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            System.out.println("在读取数据库" + dbName + "的colJudgeDic.txt文件时发生错误!");
            e.printStackTrace();
            return null;
        }
        return colJudgeDic;
    }

    /**
     * 读取多对多关联表或者存在多对一情况的表到关系的映射
     * 目前文件路径是写死在程序里面的,后期如果有机会可以考虑修改成配置文件等等
     */
    private HashMap<String,Relation> getRelTbToRelMap(){
        BufferedReader reader = null;
        HashMap<String,Relation> relTbToRelMap = null;
        try{
            reader = new BufferedReader(new FileReader("./src/main/resources/" + dbName + "/relTbToRelMap.txt"));
            relTbToRelMap = new HashMap<>();
            String line = reader.readLine();
            while(line != null && !line.equals("")){
                String[] lineArray = line.trim().split(":");
                boolean nTonN = lineArray[1].equals("n-n") ? true : false;
                if(nTonN){
                    String[] details = lineArray[2].split("-");
                    relTbToRelMap.put(lineArray[0],new Relation(details[1],details[0],details[2],nTonN));
                }else{
                    //此处添加处理多对一的情况
                }
                line = reader.readLine();
            }
        } catch (FileNotFoundException e) {
            System.out.println("找不到数据库" + dbName + "的relTbToRelMap.txt文件");
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            System.out.println("在读取数据库" + dbName + "的relTbToRelMap.txt文件时发生错误!");
            e.printStackTrace();
            return null;
        }
        return relTbToRelMap;
    }

    /**
     * 得到数据库中所有的单主键实体表的表名和主键名映射
     * @return 单主键实体表表名与其主键名的映射
     */
    private HashMap<String,String> getEntityTableMap(){
        BufferedReader reader = null;
        HashMap<String,String> entityTableMap = null;
        try {
            reader = new BufferedReader(new FileReader("./src/main/resources/" + dbName + "/entityTableMap.txt"));
            entityTableMap = new HashMap<>();
            String line = reader.readLine();
            while(line != null && !line.equals("")){
                String[] lineArray = line.trim().split(":");
                entityTableMap.put(lineArray[0],lineArray[1]);  //表名为键,主键名为值
                line = reader.readLine();
            }
        } catch (FileNotFoundException e) {
            System.out.println("找不到数据库" + dbName + "的entityTableMap.txt文件");
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            System.out.println("在读取数据库" + dbName + "的entityTableMap.txt文件时发生错误!");
            e.printStackTrace();
            return null;
        }
        return entityTableMap;
    }

    /**
     * 得到数据库中所有单主键实体表的表名和描述表中实例可读名称的列名
     * @return 单主键实体表名和描述表名实例可读名称的列名映射
     */
    private HashMap<String,String> getEntityDescMap(){
        BufferedReader reader = null;
        HashMap<String,String> entityDescMap = null;
        try {
            reader = new BufferedReader(new FileReader("./src/main/resources/" + dbName + "/entityDescMap.txt"));
            entityDescMap = new HashMap<>();
            String line = reader.readLine();
            while(line != null && !line.equals("")){
                String[] lineArray = line.trim().split(":");
                entityDescMap.put(lineArray[0],lineArray[1]);  //表名为键,描述列名为值
                line = reader.readLine();
            }
        } catch (FileNotFoundException e) {
            System.out.println("找不到数据库" + dbName + "的entityDescMap.txt文件");
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            System.out.println("在读取数据库" + dbName + "的entityDescMap.txt文件时发生错误!");
            e.printStackTrace();
            return null;
        }
        return entityDescMap;
    }
}
