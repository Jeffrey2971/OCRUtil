package com.jeffrey;

import com.baidu.aip.ocr.AipOcr;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.tika.Tika;
import org.apache.tika.utils.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.InputMismatchException;
import java.util.Properties;
import java.util.Scanner;

/**
 * @author jeffrey
 * @since JDK 1.8
 */

@Slf4j
public class Main {

    private static final ArrayList<String> SUPPORT_TYPES, FILE_FEATURES_LISTS;
    private static final ArrayList<File> FOUND_FILES;
    private static String APP_ID;
    private static String API_KEY;
    private static String SECRET_KEY;
    private static String FEATURES_STR;
    private static Float FEATURES_FLOAT;
    private static Integer OPERATION;
    private static File WORK_PATH;
    private static File TARGET_PATH;
    /**
     * inputFlag 是正确读取当前程序目录下的 ocr-conf.properties 文件后设为 true，为 false 则表示要按照正常逻辑输入参数
     */
    private static boolean inputFlag;

    static {


        System.out.println("\n这个程序可以找到指定目录中所有内含有指定特征字符串文字信息的照片，并根据给定的相似度进行指定的方式处理它们，\n程序采用百度 AI 中的 OCR 技术识别文字信息，" +
                "因此需先到 https://cloud.baidu.com/ 注册一个文字识别应用，\n并在稍后输入注册后得到的三个基础信息 APPID、API_KEY、SECRET_KEY，程序支持图片格式有 jpg / jpeg / png / bmp\n" +
                "如在程序当前目录下存在名为 ocr-conf.properties 文件，那么程序会优先读取该配置文件中的内容，如果读取失败将按照正常输入逻辑【ocr-conf.properties 配置文件中需要含有以下键值对：app_id=申请 OCR 应用后得到的 APPID ｜ api_key=申请 OCR 应用后得到的 API_KEY ｜ secret_key=申请 OCR 应用后得到的SECRET_KEY ｜ work_path=目标工作路径 ｜ features_str=特征字符串（用于和每一张图片的文字信息进行相似度比对） ｜ features_float=特征值（用于特征字符串和每一张图片的文字信息进行相似度比对） ｜ option=目标图片处理方式（1：删除；2：复制；3：移动，请输入对应的数字） ｜ target_path=输入目标图片复制或移动时的指定位置（图片处理方式为 1 时不需要填写此项）】\n\n");


        File conf = new File(getJarParentPath(Main.class), "ocr-conf.properties");

        if (conf.exists() && conf.isFile() && conf.canRead()) {

            log.info("当前目录下发现 ocr-conf.properties 配置文件");

            try {
                Properties properties = new Properties();
                properties.load(Files.newInputStream(conf.toPath()));
                APP_ID = properties.getProperty("app_id");
                API_KEY = properties.getProperty("api_key");
                SECRET_KEY = properties.getProperty("secret_key");
                WORK_PATH = new File(properties.getProperty("work_path"));
                FEATURES_STR = properties.getProperty("features_str");
                FEATURES_FLOAT = Float.parseFloat(properties.getProperty("features_float"));
                OPERATION = Integer.parseInt(properties.getProperty("option"));
                if (OPERATION == 2 || OPERATION == 3) {
                    TARGET_PATH = new File(properties.getProperty("target_path"));
                }
                inputFlag = true;
                inputOrCheck();
            } catch (Exception ex) {
                log.warn("虽然指定了 ocr-conf.properties 配置文件，但是在解析它时出现了错误，请手动输入参数");
                log.warn("错误类型：{}", ex.getClass().getTypeName());
                log.warn("错误信息：{}", ex.getMessage());
                inputFlag = false;
                inputOrCheck();
            }
        } else {
            log.warn("在当前程序目录下 {} 没有找到 ocr-conf.properties 配置文件，请手动输入参数", conf);
            log.info(conf.toString());
            inputFlag = false;
            inputOrCheck();
        }

        FOUND_FILES = new ArrayList<>();
        FILE_FEATURES_LISTS = new ArrayList<>();
        SUPPORT_TYPES = new ArrayList<>(4);

        SUPPORT_TYPES.add("image/jpeg");
        SUPPORT_TYPES.add("image/jpg");
        SUPPORT_TYPES.add("image/png");
        SUPPORT_TYPES.add("image/bmp");
    }

    /**
     * 根据给定的 Class 找到当前运行程序运行路径
     *
     * @param currentClassType 类
     * @return 运行时路径
     */
    private static File getJarParentPath(Class<?> currentClassType) {

        File conf = new File(System.getProperty("java.class.path"));

        if (conf.exists()) return conf.getParentFile();

        String jarLocationPath = currentClassType.getProtectionDomain().getCodeSource().getLocation().getPath();

        if (jarLocationPath.startsWith("file:")) jarLocationPath = jarLocationPath.replace("file:", "");

        if (System.getProperty("os.name").contains("dows")) {
            conf = new File(jarLocationPath.substring(1));
            return conf.exists() ? conf.getParentFile() : null;
        }

        if (jarLocationPath.contains("jar")) {
            conf = new File(jarLocationPath.substring(0, jarLocationPath.indexOf(".")).substring(0, jarLocationPath.lastIndexOf("/")));
            return conf.exists() ? conf.getParentFile() : null;
        }

        conf = new File(jarLocationPath.replace("target/classes/", ""));
        return conf.exists() ? conf.getParentFile() : null;
    }

    @SneakyThrows
    public static void main(String[] args) {

        log.info("开始处理文件");

        search(WORK_PATH);

        if (FOUND_FILES.size() == 0) {
            log.info("没有找到任何有效文件，程序退出");
            return;
        }

        AipOcr client = new AipOcr(APP_ID, API_KEY, SECRET_KEY);


        for (File item : FOUND_FILES) {

            log.info("处理文件：{}", item);

            JSONObject res = client.basicGeneral(item.toString(), null);

            if (!res.has("words_result")) {
                log.info("忽略：图片内容文字信息为空或请求失败 {}", item);
                continue;
            }

            JSONArray words_result = res.getJSONArray("words_result");
            StringBuilder str = new StringBuilder();

            for (Object line : words_result) {
                str.append(((JSONObject) line).get("words"));
            }

            float similarityRatio = EditDistance.getSimilarityRatio(FEATURES_STR, str.toString());

            if (similarityRatio >= FEATURES_FLOAT) {

                log.info("发现相似图片：【名称 {} ｜ 图片文字内容和给定特征字符串相似度 {}】", item.getName(), similarityRatio);

                switch (OPERATION) {
                    case 1:
                        if (FileUtils.delete(item).exists()) {
                            log.info("成功删除文件：{}", item);
                        } else {
                            log.warn("删除文件失败：{}", item);
                        }
                        break;
                    case 2:
                        FileUtils.copyFile(item, new File(TARGET_PATH, item.getName()));
                        log.info("成功拷贝文件：{}", item);
                        break;
                    case 3:
                        FileUtils.moveFile(item, new File(TARGET_PATH, item.getName()));
                        log.info("成功移动文件：{}", item);
                    default:
                        throw new RuntimeException("选择图片操作时出现了异常，程序退出");
                }
            }
        }
    }


    private static void inputOrCheck() {

        if (!inputFlag) {

            Scanner scanner = new Scanner(System.in);

            log.info("输入 APP ID：");
            APP_ID = scanner.nextLine();

            log.info("输入 API KEY：");
            API_KEY = scanner.nextLine();

            log.info("输入 Secret Key：");
            SECRET_KEY = scanner.nextLine();

            log.info("输入工作路径：");
            WORK_PATH = new File(scanner.nextLine());

            log.info("输入特征字符串（用于和每一张图片的文字信息进行相似度比对）：");
            FEATURES_STR = scanner.nextLine();

            log.info("输入特征值（用于特征字符串和每一张图片的文字信息进行相似度比对）：");
            try {
                FEATURES_FLOAT = scanner.nextFloat();
                scanner.nextLine();
            } catch (NumberFormatException | InputMismatchException e) {
                log.error("输入的特征相似度不是一个有效的浮点值");
                System.exit(0);
            }

            log.info("输入目标图片处理方式（1：删除；2：复制；3：移动，请输入对应的数字）：");
            try {
                OPERATION = scanner.nextInt();
                scanner.nextLine();
            } catch (NumberFormatException | InputMismatchException e) {
                log.error("处理图片的方式不能为空，应输入数字 1 至 3");
                System.exit(0);
            }

            if (OPERATION == 2 || OPERATION == 3) {
                log.info("输入目标图片复制或移动时的指定位置：");
                TARGET_PATH = new File(scanner.nextLine());
            }
        }

        if (StringUtils.isBlank(APP_ID) || StringUtils.isBlank(API_KEY) || StringUtils.isBlank(SECRET_KEY)) {
            log.error("请先到 https://cloud.baidu.com/ 注册一个文字识别应用，并输入注册后得到的三个基础信息 APPID、API_KEY、SECRET_KEY");
            System.exit(0);
        }

        if (!WORK_PATH.exists() || !WORK_PATH.isDirectory() || !WORK_PATH.canExecute()) {
            log.error("指定的工作路径不可用，它可能不是一个目录或它不存在、无法访问");
            System.exit(0);
        }

        if ((OPERATION == 2 || OPERATION == 3) && !TARGET_PATH.exists() || !TARGET_PATH.isDirectory() || !TARGET_PATH.canExecute()
                || (WORK_PATH.toString().equalsIgnoreCase(TARGET_PATH.toString()))) {
            log.error("指定的目标图片复制或移动路径不可用，它可能不存在、为一个文件而不是文件夹、无法访问、工作路径和目标复制或移动路径相同");
            System.exit(0);
        }

        if (StringUtils.isBlank(FEATURES_STR)) {
            log.error("特征字符串不能为空");
            System.exit(0);
        }

    }

    @SneakyThrows
    private static void search(File file) {
        File[] files = file.listFiles();
        if (files != null) {
            for (File item : files) {
                if (item.isDirectory()) {
                    search(item);
                } else {
                    String type = new Tika().detect(item).toLowerCase();
                    String fileFeatures = getFileFeatures(item);
                    if (SUPPORT_TYPES.contains(type) && !FILE_FEATURES_LISTS.contains(fileFeatures)) {
                        FOUND_FILES.add(item);
                        FILE_FEATURES_LISTS.add(fileFeatures);
                        log.info("找到：【文件 {} | 类型 {} | 特征 {}】", item.getName(), type, fileFeatures);
                    } else {
                        log.info("忽略：【文件 {} | 类型 {} | 特征 {}】", item.getName(), type, fileFeatures);
                    }
                }
            }
        }
    }

    @SneakyThrows
    private static String getFileFeatures(File file) {
        return DigestUtils.md5Hex(Files.newInputStream(file.toPath()));
    }
}
