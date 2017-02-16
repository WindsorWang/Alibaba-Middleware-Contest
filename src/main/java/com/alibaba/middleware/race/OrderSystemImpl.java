package com.alibaba.middleware.race;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.alibaba.middleware.race.entity.KV;
import com.alibaba.middleware.race.entity.ResultImpl;
import com.alibaba.middleware.race.entity.Row;
import com.alibaba.middleware.race.index.BuyerGoodIndexCreator;
import com.alibaba.middleware.race.index.BuyerGoodIndexHandler;
import com.alibaba.middleware.race.index.OrderIndexCreator;
import com.alibaba.middleware.race.utils.CommonConstants;
import com.alibaba.middleware.race.utils.ExtendBufferedReader;
import com.alibaba.middleware.race.utils.ExtendBufferedWriter;
import com.alibaba.middleware.race.utils.HashUtil;
import com.alibaba.middleware.race.utils.IOUtils;
import com.alibaba.middleware.race.utils.IndexBuffer;
import com.alibaba.middleware.race.utils.SimpleLRUCache;
import com.alibaba.middleware.race.utils.StringUtils;

/**
 * 订单系统的demo实现，订单数据全部存放在内存中，用简单的方式实现数据存储和查询功能
 * 
 * @author wangxiang@alibaba-inc.com
 *
 */
public class OrderSystemImpl implements OrderSystem {

	private List<String> orderFiles;
	private List<String> goodFiles;
	private List<String> buyerFiles;
	
	private String query1Path;
	private String query2Path;
	private String query3Path;
//	private String query4Path;
	
	private String buyersPath;
	private String goodsPath;
	
	private ExecutorService multiQueryPool2;
	private ExecutorService multiQueryPool3;
	private ExecutorService multiQueryPool4;
	
	/**
	 * 这个数据记录每个文件的行当前写入的record数量，当大于INDEX_LINE_RECORDS的时候，换行
	 */
	private int[] query1LineRecords;
	private ExtendBufferedWriter[] query1IndexWriters;	
	
	private int[] query2LineRecords;
	private ExtendBufferedWriter[] query2IndexWriters;	
	
	private int[] query3LineRecords;
	private ExtendBufferedWriter[] query3IndexWriters;
	
	private ExtendBufferedWriter[] buyersIndexWriters;
	private HashMap<String,String> buyerMemoryIndexMap;
	private SimpleLRUCache<String, String> buyersCache;
	
	private ExtendBufferedWriter[] goodsIndexWriters;
	private SimpleLRUCache<String, String> goodsCache;
	private HashMap<String,String> goodMemoryIndexMap; 
	private boolean buyerGoodInMemory = true;
	private BuyerGoodIndexHandler bgHandler;
	
	private volatile boolean isConstructed;
	
	

	
	/**
	 * 根据参数新建新建文件 目录等操作
	 */
	public OrderSystemImpl() {
		bgHandler = new BuyerGoodIndexHandler();
			
		this.query1LineRecords = new int[CommonConstants.QUERY1_ORDER_SPLIT_SIZE];
		this.query2LineRecords = new int[CommonConstants.QUERY2_ORDER_SPLIT_SIZE];
		this.query3LineRecords = new int[CommonConstants.QUERY3_ORDER_SPLIT_SIZE];
		
		this.goodsCache = bgHandler.getGoodsCache();
		this.goodMemoryIndexMap = bgHandler.getGoodMemoryIndexMap();	
		this.buyersCache = bgHandler.getBuyersCache();
		this.buyerMemoryIndexMap = bgHandler.getBuyerMemoryIndexMap();

		this.isConstructed = false;
		
		this.multiQueryPool2 = Executors.newFixedThreadPool(8);
		this.multiQueryPool3 = Executors.newFixedThreadPool(8);
		this.multiQueryPool4 = Executors.newFixedThreadPool(8);
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		// init order system
		List<String> orderFiles = new ArrayList<String>();
		List<String> buyerFiles = new ArrayList<String>();

		List<String> goodFiles = new ArrayList<String>();
		List<String> storeFolders = new ArrayList<String>();

		orderFiles.add("prerun_data\\order.0.0");
		orderFiles.add("prerun_data\\order.1.1");
		orderFiles.add("prerun_data\\order.2.2");
		orderFiles.add("prerun_data\\order.0.3");
		buyerFiles.add("prerun_data\\buyer.0.0");
		buyerFiles.add("prerun_data\\buyer.1.1");
		goodFiles.add("prerun_data\\good.0.0");
		goodFiles.add("prerun_data\\good.1.1");
		goodFiles.add("prerun_data\\good.2.2");
		storeFolders.add("./data2");

		storeFolders.add("./data");
		OrderSystemImpl os = new OrderSystemImpl();
		os.construct(orderFiles, buyerFiles, goodFiles, storeFolders);

		// 用例
		if (true){
			long start = System.currentTimeMillis();
			long orderid = 609670049;
			System.out.println("\n查询订单号为" + orderid + "的订单");
			List<String> keys = new ArrayList<>();
			keys.add("description");
			System.out.println(os.queryOrder(orderid, keys));
			System.out.println(System.currentTimeMillis()-start);
			System.out.println("\n查询订单号为" + orderid + "的订单，查询的keys为空，返回订单，但没有kv数据");
			System.out.println(os.queryOrder(orderid, new ArrayList<String>()));
		}

		if (true){
			long start = System.currentTimeMillis();
			long orderid = 609670049;
			System.out.println("\n查询订单号为" + orderid + "的订单的contactphone, buyerid,foo, done, price字段");
			List<String> queryingKeys = new ArrayList<String>();
			queryingKeys.add("contactphone");
			queryingKeys.add("buyerid");
			queryingKeys.add("foo");
			queryingKeys.add("done");
			queryingKeys.add("price");
			Result result = os.queryOrder(orderid, queryingKeys);
			System.out.println(result);
			System.out.println("\n查询订单号不存在的订单");
			result = os.queryOrder(1111, queryingKeys);
			if (result == null) {
				System.out.println(1111 + " order not exist");
			}
			System.out.println(System.currentTimeMillis() - start);
		}

		if (true){
			long start = System.currentTimeMillis();
			String buyerid = "tp-b0a2-fd0ca6720971";
			long startTime = 1467791748;
			long endTime = 1481816836;		
			Iterator<Result> it = os.queryOrdersByBuyer(startTime, endTime, buyerid);
			
			System.out.println("\n查询买家ID为" + buyerid + "的一定时间范围内的订单");
			while (it.hasNext()) {
//				System.out.println(it); 
				it.next();
			}
			System.out.println("time:"+(System.currentTimeMillis() - start));
		}

		if (true){
			String goodid = "aye-8837-3aca358bfad3";
			String salerid = "almm-b250-b1880d628b9a";
			System.out.println("\n查询商品id为" + goodid + "，商家id为" + salerid + "的订单");
			long start = System.currentTimeMillis();
			List<String> keys = new ArrayList<>();
			keys.add("address");
			Iterator it = os.queryOrdersBySaler(salerid, goodid, keys);
			while (it.hasNext()) {
				it.next();
			}
			System.out.println(System.currentTimeMillis()-start);
		}	
		if (true){
			long start = System.currentTimeMillis();
			String goodid = "dd-a27d-835565dfb080";
			String attr = "a_b_3503";
			System.out.println("\n对商品id为" + goodid + "的 " + attr + "字段求和");
			System.out.println(os.sumOrdersByGood(goodid, attr));
			System.out.println(System.currentTimeMillis() -start);
		}	
		if (true){
			String goodid = "good_d191eeeb-fed1-4334-9c77-3ee6d6d66aff";
			String attr = "app_order_33_0";
			System.out.println("\n对商品id为" + goodid + "的 " + attr + "字段求和");
			System.out.println(os.sumOrdersByGood(goodid, attr));
			
			attr = "done";
			System.out.println("\n对商品id为" + goodid + "的 " + attr + "字段求和");
			KeyValue sum = os.sumOrdersByGood(goodid, attr);
			if (sum == null) {
				System.out.println("由于该字段是布尔类型，返回值是null");
			}
			
			attr = "foo";
			System.out.println("\n对商品id为" + goodid + "的 " + attr + "字段求和");
			sum = os.sumOrdersByGood(goodid, attr);
			if (sum == null) {
				System.out.println("由于该字段不存在，返回值是null");
			}
		}
	}


	public void construct(Collection<String> orderFiles, Collection<String> buyerFiles, Collection<String> goodFiles,
			Collection<String> storeFolders) throws IOException, InterruptedException {
		System.out.println("orders:");
		for(String s:orderFiles) {
			System.out.println(s);
		}
		System.out.println("buyers:");
		for(String s:buyerFiles) {
			System.out.println(s);
		}
		System.out.println("goods:");
		for(String s:goodFiles) {
			System.out.println(s);
		}
		System.out.println("storeFolers:");
		for(String s:storeFolders) {
			System.out.println(s);
		}
		
		this.orderFiles = new ArrayList<>(orderFiles);
		this.buyerFiles = new ArrayList<>(buyerFiles);
		this.goodFiles = new ArrayList<>(goodFiles);
		long start = System.currentTimeMillis();
		constructDir(storeFolders);
		final long dir = System.currentTimeMillis();
		System.out.println("construct dir time:" + (dir-start));
		
		
		// 主要对第一阶段的index time进行限制
		final CountDownLatch latch = new CountDownLatch(1);
		new Thread() {
			@Override
			public void run() {
				
				constructWriterForIndexFile();
				long writer = System.currentTimeMillis();
				System.out.println("writer time:" + (writer - dir));
				
				constructHashIndex();
				long index = System.currentTimeMillis();
				System.out.println("index time:" + (index - writer));
				
				
				closeWriter();	
				long closeWriter = System.currentTimeMillis();
				System.out.println("close time:" + (closeWriter - index));
				System.out.println("construct KO");
				latch.countDown();
				isConstructed = true;
			}
		}.start();
		
		latch.await(59 * 60 + 45, TimeUnit.SECONDS);
		System.out.println("construct return,time:" + (System.currentTimeMillis() - start));	
	}
	
	private void constructHashIndex() {
		// 5个线程各自完成之后 该函数才能返回
		CountDownLatch latch = new CountDownLatch(5);
		new Thread(new OrderIndexCreator("orderid", query1IndexWriters, query1LineRecords,orderFiles, CommonConstants.QUERY1_ORDER_SPLIT_SIZE,
				CommonConstants.ORDERFILE_BLOCK_SIZE, latch,new String[]{"orderid"})).start();
		new Thread(new OrderIndexCreator("buyerid", query2IndexWriters, query2LineRecords, orderFiles, CommonConstants.QUERY2_ORDER_SPLIT_SIZE,
				CommonConstants.ORDERFILE_BLOCK_SIZE, latch,new String[]{"buyerid","createtime"})).start();
		new Thread(new OrderIndexCreator("goodid", query3IndexWriters, query3LineRecords ,orderFiles, CommonConstants.QUERY3_ORDER_SPLIT_SIZE,
				CommonConstants.ORDERFILE_BLOCK_SIZE, latch, new String[]{"goodid"})).start();

		new Thread(new BuyerGoodIndexCreator("buyerid" ,buyerFiles, CommonConstants.OTHERFILE_BLOCK_SIZE, latch, bgHandler)).start();
		new Thread(new BuyerGoodIndexCreator("goodid", goodFiles, CommonConstants.OTHERFILE_BLOCK_SIZE, latch, bgHandler)).start();
		
		try {
			latch.await();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	

	

	/**
	 * 创建构造索引的writer
	 */
	private void constructWriterForIndexFile() {
		// 创建4种查询的4中索引文件和买家 商品信息的writer
		this.query1IndexWriters = new ExtendBufferedWriter[CommonConstants.QUERY1_ORDER_SPLIT_SIZE];
		for (int i = 0; i < CommonConstants.QUERY1_ORDER_SPLIT_SIZE; i++) {
			try {
				String file = this.query1Path + File.separator + i + CommonConstants.INDEX_SUFFIX;
				RandomAccessFile ran = new RandomAccessFile(file, "rw");
				ran.setLength(1024 * 1024 * 80);
				ran.close();
				query1IndexWriters[i] = IOUtils.createWriter(file, CommonConstants.INDEX_BLOCK_SIZE);
			} catch (IOException e) {

			}
		}

		this.query2IndexWriters = new ExtendBufferedWriter[CommonConstants.QUERY2_ORDER_SPLIT_SIZE];
		for (int i = 0; i < CommonConstants.QUERY2_ORDER_SPLIT_SIZE; i++) {
			try {
				String file = this.query2Path + File.separator + i + CommonConstants.INDEX_SUFFIX;
				RandomAccessFile ran = new RandomAccessFile(file, "rw");
				ran.setLength(1024 * 1024 * 80);
				ran.close();
				query2IndexWriters[i] = IOUtils.createWriter(file, CommonConstants.INDEX_BLOCK_SIZE);
			} catch (IOException e) {

			}
		}
		
		this.query3IndexWriters = new ExtendBufferedWriter[CommonConstants.QUERY3_ORDER_SPLIT_SIZE];
		for (int i = 0; i < CommonConstants.QUERY3_ORDER_SPLIT_SIZE; i++) {
			try {
				String file = this.query3Path + File.separator + i + CommonConstants.INDEX_SUFFIX;
				RandomAccessFile ran = new RandomAccessFile(file, "rw");
				ran.setLength(1024 * 1024 * 80);
				ran.close();
				query3IndexWriters[i] = IOUtils.createWriter(file, CommonConstants.INDEX_BLOCK_SIZE);
			} catch (IOException e) {

			}
		}

		if (!buyerGoodInMemory) {
			this.buyersIndexWriters = new ExtendBufferedWriter[CommonConstants.OTHER_SPLIT_SIZE];
			for (int i = 0; i < CommonConstants.OTHER_SPLIT_SIZE; i++) {
				try {
					String file = this.buyersPath + File.separator + i+ CommonConstants.INDEX_SUFFIX;
					RandomAccessFile ran = new RandomAccessFile(file, "rw");
					ran.setLength(1024 * 1024 * 10);
					ran.close();
					buyersIndexWriters[i] = IOUtils.createWriter(file, CommonConstants.INDEX_BLOCK_SIZE);
				} catch (IOException e) {
	
				}
			}
		}
		if (!buyerGoodInMemory) {	
			this.goodsIndexWriters = new ExtendBufferedWriter[CommonConstants.OTHER_SPLIT_SIZE];
			for (int i = 0; i < CommonConstants.OTHER_SPLIT_SIZE; i++) {
				try {
					String file = this.goodsPath + File.separator + i + CommonConstants.INDEX_SUFFIX;
					RandomAccessFile ran = new RandomAccessFile(file, "rw");
					ran.setLength(1024 * 1024 * 10);
					ran.close();
					goodsIndexWriters[i] = IOUtils.createWriter(file, CommonConstants.INDEX_BLOCK_SIZE);
				} catch (IOException e) {
	
				}
			}
		}
	}
	
	/**
	 * 索引创建目录
	 * @param storeFolders
	 */
	private void constructDir(Collection<String> storeFolders) {
		List<String> storeFoldersList = new ArrayList<>(storeFolders);
		
		// 4种查询的3种索引文件和买家、商品信息平均分到不同的路径上
		int len = storeFoldersList.size();
		int storeIndex = 0;

		this.query1Path = storeFoldersList.get(storeIndex) + File.separator + CommonConstants.QUERY1_PREFIX;
		File query1File = new File(query1Path);
		if (!query1File.exists()) {
			query1File.mkdirs();
		}
		storeIndex++;
		storeIndex %= len;

		this.query2Path = storeFoldersList.get(storeIndex) + File.separator + CommonConstants.QUERY2_PREFIX;
		File query2File = new File(query2Path);
		if (!query2File.exists()) {
			query2File.mkdirs();
		}
		storeIndex++;
		storeIndex %= len;

		this.query3Path = storeFoldersList.get(storeIndex) + File.separator + CommonConstants.QUERY3_PREFIX;
		File query3File = new File(query3Path);
		if (!query3File.exists()) {
			query3File.mkdirs();
		}
		storeIndex++;
		storeIndex %= len;

		if (!buyerGoodInMemory) {
			this.buyersPath = storeFoldersList.get(storeIndex) + File.separator + CommonConstants.BUYERS_PREFIX;
			File buyersFile = new File(buyersPath);
			if (!buyersFile.exists()) {
				buyersFile.mkdirs();
			}
			storeIndex++;
			storeIndex %= len;
			System.out.println(storeFoldersList.get(storeIndex)+",index:"+storeIndex);
			this.goodsPath = storeFoldersList.get(storeIndex) + File.separator + CommonConstants.GOODS_PREFIX;
			File goodsFile = new File(goodsPath);
			if (!goodsFile.exists()) {
				goodsFile.mkdirs();
			}
		}
	}

	public Result queryOrder(long orderId, Collection<String> keys) {
		while (this.isConstructed == false) {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
//		final long start = System.currentTimeMillis();
//		int count  = query1Count.incrementAndGet();
//		if (count % CommonConstants.QUERY_PRINT_COUNT == 0) {
//			System.out.println("query1count:" + query1Count.get());	
//		}
		Row query = new Row();
		query.putKV("orderid", orderId);

		Row orderData = null;
		

		int index = HashUtil.indexFor(HashUtil.hashWithDistrub(orderId), CommonConstants.QUERY1_ORDER_SPLIT_SIZE);
		String indexFile = this.query1Path + File.separator + index + CommonConstants.INDEX_SUFFIX;
		
//		query1Lock.lock();
//			HashMap<String,String> indexMap = null;
		String[] indexArray = null;
		try (ExtendBufferedReader indexFileReader = IOUtils.createReader(indexFile, CommonConstants.INDEX_BLOCK_SIZE)){
			// 可能index文件就没有
			String sOrderId = String.valueOf(orderId);
			String line = indexFileReader.readLine();
			while (line != null) {

				if (line.startsWith(sOrderId)) {
					int p = line.indexOf(':');
					indexArray = StringUtils.getIndexInfo(line.substring(p + 1));
					break;
				}
				line = indexFileReader.readLine();
				
			}

			// 说明文件中没有这个orderId的信息
			if (indexArray == null) {
				System.out.println("query1 can't find order:");
				return null;
			}
			String file = this.orderFiles.get(Integer.parseInt(indexArray[0]));
			Long offset = Long.parseLong(indexArray[1]);
			byte[] content = new byte[Integer.valueOf(indexArray[2])];
			try (RandomAccessFile orderFileReader = new RandomAccessFile(file, "r")) {
				orderFileReader.seek(offset);
				orderFileReader.read(content);
				line = new String(content);
				orderData = StringUtils.createKVMapFromLine(line, CommonConstants.SPLITTER);
				
			} catch (IOException e) {
				// 忽略
			}
		} catch(IOException e) {
			
		}
		
		if (orderData == null) {
			return null;
		}
		ResultImpl result= createResultFromOrderData(orderData, createQueryKeys(keys));
//		if (count % CommonConstants.QUERY_PRINT_COUNT == 0) {
//			System.out.println("query1 all time:" + (System.currentTimeMillis() - start));
//		}
		return result;
	}
	
	private void closeWriter() {
		try {

			for (ExtendBufferedWriter bw : query1IndexWriters) {
				bw.close();
			}

			for (ExtendBufferedWriter bw : query2IndexWriters) {
				bw.close();
			}

			for (ExtendBufferedWriter bw : query3IndexWriters) {
				bw.close();
			}
			
			if (!buyerGoodInMemory) {
				for (ExtendBufferedWriter bw : buyersIndexWriters) {
					bw.close();
				}
			}
			
			if (!buyerGoodInMemory) {
				for (ExtendBufferedWriter bw : goodsIndexWriters) {
					bw.close();
				}
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	
	private Row getGoodRowFromOrderData(Row orderData) {
		// Row goodData = goodDataStoredByGood.get(new
		// ComparableKeys(comparableKeysOrderingByGood, goodQuery));
		Row goodData = null;
		String goodId = orderData.getKV("goodid").valueAsString();
		String cachedString = goodsCache.get(goodId);
		if (cachedString != null) {
			goodData = StringUtils.createKVMapFromLine(cachedString, CommonConstants.SPLITTER);
		} else {
			String[] indexArray = null;
//			HashMap<String,String> indexMap = null;
			if (!this.buyerGoodInMemory) {
				int index = HashUtil.indexFor(HashUtil.hashWithDistrub(goodId), CommonConstants.OTHER_SPLIT_SIZE);
				String goodIndexFile = this.goodsPath + File.separator + index + CommonConstants.INDEX_SUFFIX;
				try(ExtendBufferedReader indexFileReader = IOUtils.createReader(goodIndexFile, CommonConstants.INDEX_BLOCK_SIZE)){
					String line = indexFileReader.readLine();
					while (line != null) {
						if (line.startsWith(goodId)) {
							int p = line.indexOf(':');
							indexArray = StringUtils.getIndexInfo(line.substring(p + 1));
							break;
						}
						line = indexFileReader.readLine();			
					}
				} catch(IOException e) {
					
				}
			} else {
				String line = this.goodMemoryIndexMap.get(goodId);
//				System.out.println(line);
				if (line != null) {
					indexArray = StringUtils.getIndexInfo(line);
				}
			}
			// 由于现在query4的求和可能直接查good信息，因此此处代表不存在对应的信息
			if (indexArray == null) {
				return null;
			}
			String file = this.goodFiles.get(Integer.parseInt(indexArray[0]));
			Long offset = Long.parseLong(indexArray[1]);
			byte[] content = new byte[Integer.valueOf(indexArray[2])];
			try (RandomAccessFile goodFileReader = new RandomAccessFile(file, "r")) {
				goodFileReader.seek(offset);
				goodFileReader.read(content);
				String line = new String(content);
				goodData = StringUtils.createKVMapFromLine(line, CommonConstants.SPLITTER);
				goodsCache.put(goodId, line);
			} catch (IOException e) {
				// 忽略
			} 
		}
		return goodData;
	}
	
	private Row getBuyerRowFromOrderData(Row orderData) {
		Row buyerData = null;
		
		String buyerId = orderData.getKV("buyerid").valueAsString();
		String cachedString = buyersCache.get(buyerId);
		if (cachedString != null) {
			buyerData = StringUtils.createKVMapFromLine(cachedString, CommonConstants.SPLITTER);
		} else {
			String[] indexArray = null;
			if (!this.buyerGoodInMemory) {
				int index = HashUtil.indexFor(HashUtil.hashWithDistrub(buyerId), CommonConstants.OTHER_SPLIT_SIZE);
				String buyerIndexFile = this.buyersPath + File.separator + index + CommonConstants.INDEX_SUFFIX;
				try (ExtendBufferedReader indexFileReader = IOUtils.createReader(buyerIndexFile, CommonConstants.INDEX_BLOCK_SIZE)){
					String line = indexFileReader.readLine();
					while (line != null) {
						if (line.startsWith(buyerId)) {
							int p = line.indexOf(':');
							indexArray = StringUtils.getIndexInfo(line.substring(p + 1));
							break;
						}
						line = indexFileReader.readLine();			
					}
					// 如果能查到其他信息 则对应的值buyer和order一定存在，此处不需要判断为null
	
				} catch(IOException e) {
					
				}
			} else {
				String line = this.buyerMemoryIndexMap.get(buyerId);
				if (line != null) {
					indexArray = StringUtils.getIndexInfo(line);
				}
			}
			String file = this.buyerFiles.get(Integer.parseInt(indexArray[0]));
			Long offset = Long.parseLong(indexArray[1]);
			byte[] content = new byte[Integer.valueOf(indexArray[2])];
			try (RandomAccessFile buyerFileReader = new RandomAccessFile(file, "r")) {
				buyerFileReader.seek(offset);
				buyerFileReader.read(content);
				String line = new String(content);
				buyerData = StringUtils.createKVMapFromLine(line, CommonConstants.SPLITTER);
				buyersCache.put(buyerId, line);

			} catch (IOException e) {
				// 忽略
			} 
		}
		return buyerData;
	}
	/**
	 * join操作，根据order订单中的buyerid和goodid进行join
	 * 
	 * @param orderData
	 * @param keys
	 * @return
	 */
	private ResultImpl createResultFromOrderData(Row orderData, Collection<String> keys) {
		String tag = "all";
		Row buyerData = null;
		Row goodData = null;
		// keys为null或者keys 不止一个的时候和以前的方式一样 all join
		if (keys != null && keys.size() == 1) {
			tag = getKeyJoin((String)keys.toArray()[0]);
		}
		// all的话join两次 buyer或者good join1次 order不join
		if (tag.equals("buyer") || tag.equals("all")) {
			buyerData = getBuyerRowFromOrderData(orderData);
		}
		if (tag.equals("good") || tag.equals("all")) {
			goodData = getGoodRowFromOrderData(orderData);
		}
		return ResultImpl.createResultRow(orderData, buyerData, goodData, createQueryKeys(keys));
	}
	/**
	 * 判断key的join 只对单个key有效果 order代表不join
	 * buyer代表只join buyer表 good表示只join good表
	 * 返回all代表无法判断 全部join
	 * @param key
	 * @return
	 */
	private String getKeyJoin(String key) {
		if(key.startsWith("a_b_")) {
			return "buyer";
		} 
		
		if (key.startsWith("a_g_")) {
			return "good";
		}
		
		if (key.startsWith("a_o_")) {
			return "order";
		}
		if (key.equals("orderid") || key.equals("buyerid") || key.equals("goodid") || key.equals("createtime")
				|| key.equals("amount") || key.equals("done") || key.equals("remark")) {
			return "order";
		}
		
		if (key.equals("buyername") || key.equals("address") || key.equals("contactphone")) {
			return "buyer";
		}
		
		if (key.equals("salerid") || key.equals("price") || key.equals("offprice") || key.equals("description") || key.equals("good_name")) {
			return "good";
		}
		
		return "all";
	}

	private HashSet<String> createQueryKeys(Collection<String> keys) {
		if (keys == null) {
			return null;
		}
		return new HashSet<String>(keys);
	}
	
	private Map<String,PriorityQueue<String[]>> createOrderDataAccessSequence(Collection<String> offsetRecords) {
		Map<String,PriorityQueue<String[]>> result = new HashMap<>(512);
		for(String e : offsetRecords) {
			int fileP = e.indexOf(' ');
			String fileIndex = e.substring(0, fileP);
			String offLenS = e.substring(fileP + 1);

			String[] offLenArray = new String[2];
			int offsetP = offLenS.indexOf(' ');
			offLenArray[0] = offLenS.substring(0, offsetP);
			offLenArray[1] = offLenS.substring(offsetP + 1);
			if (result.containsKey(fileIndex)) {
				result.get(fileIndex).offer(offLenArray);
			} else {
				PriorityQueue<String[]> resultQueue = new PriorityQueue<>(50, new Comparator<String[]>() {

					// 比较第一个String的大小即可
					@Override
					public int compare(String[] o1, String[] o2) {
						// TODO Auto-generated method stub
						return Long.parseLong(o1[0]) > Long.parseLong(o2[0])? 1 : -1 ;
					}

				});
				resultQueue.offer(offLenArray);
				result.put(fileIndex, resultQueue);
			}
		}
		return result;
	}

	public Iterator<Result> queryOrdersByBuyer(long startTime, long endTime, String buyerid) {
		while (this.isConstructed == false) {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		List<Row> buyerOrderResultList = new ArrayList<>(100);
		
		boolean validParameter = true;
		
		if (endTime <=0) {
			validParameter = false;
		}
		
		if (endTime <= startTime) {
			validParameter = false;
		}
		
		//线上测试得到有效订单的时间区间
		if (startTime > 11668409867L) {
			validParameter = false;
		}
		
		if (endTime <= 1468385553L ) {
			validParameter = false;
		}
	
		if (validParameter) {
			int index = HashUtil.indexFor(HashUtil.hashWithDistrub(buyerid), CommonConstants.QUERY2_ORDER_SPLIT_SIZE);
	//		
			String indexFile = this.query2Path + File.separator + index + CommonConstants.INDEX_SUFFIX;
						
			// 一个用户的所有order信息 key为createtime;value为file offset length
			List<String> buyerOrderList = new ArrayList<>(100);
	
			try (ExtendBufferedReader indexFileReader = IOUtils.createReader(indexFile, CommonConstants.INDEX_BLOCK_SIZE)){
				String line = indexFileReader.readLine();
				
				while (line != null) {
					// 获得一行中以<buyerid>开头的行
					if (line.startsWith(buyerid)) {
						int p = line.indexOf(':');
						String key = line.substring(0, p);
						Long createTime = Long.parseLong(key.substring(20));
						if (createTime >= startTime && createTime < endTime) {
							buyerOrderList.add(line.substring(p + 1));
						}
						
					}
					line = indexFileReader.readLine();
				}
				
				if (buyerOrderList.size() > 0) {
//					System.out.println(buyerOrderList.size());
//					Row kvMap;
					Map<String,PriorityQueue<String[]>> buyerOrderAccessSequence = createOrderDataAccessSequence(buyerOrderList);
					List<Future<List<Row>>> result = new ArrayList<>();
					for (Map.Entry<String, PriorityQueue<String[]>> e : buyerOrderAccessSequence.entrySet()) {
//						String file = this.orderFiles.get(Integer.parseInt(e.getKey()));
//	//					System.out.println("file:"+file);
//						String[] sequence;
//						try(RandomAccessFile orderFileReader = new RandomAccessFile(file, "r")) {
//							sequence = e.getValue().poll();
//							while(sequence != null) {
//								
//								Long offset = Long.parseLong(sequence[0]);
//	//							System.out.println("offset:"+offset);
//	//							System.out.println("lenth:"+Integer.valueOf(sequence[1]));
//								byte[] content = new byte[Integer.valueOf(sequence[1])];
//								orderFileReader.seek(offset);
//								orderFileReader.read(content);
//								line = new String(content);
//		
//								kvMap = StringUtils.createKVMapFromLine(line, CommonConstants.SPLITTER);
//								buyerOrderResultList.add(kvMap);
////								buyerOrderQueue.offer(kvMap);
//								sequence = e.getValue().poll();
//							}
//							
//						} 
						int fileIndex = Integer.parseInt(e.getKey());
						OrderDataSearch search = new OrderDataSearch(fileIndex, e.getValue());
						result.add(multiQueryPool2.submit(search));
						
					}
					for (Future<List<Row>> f: result) {
						try {
							List<Row> list = f.get();
							if (list != null) {
								for(Row row : list) {
									buyerOrderResultList.add(row);
								}
							}
						} catch (InterruptedException | ExecutionException e1) {
							// TODO Auto-generated catch block
							e1.printStackTrace();
						}
					}
	
//					if (count % CommonConstants.QUERY_PRINT_COUNT == 0) {
//						System.out.println("query2 original data time:" + (System.currentTimeMillis() - start) + "size:" + buyerOrderAccessSequence.size());
//					}
				} else {
//					System.out.println("query2 can't find order:" + buyerid + "," + startTime + "," + endTime);
				}
	
			} catch(IOException e) {
				
			}
		}
		// query2需要join good信息
		Row buyerRow = buyerOrderResultList.size() == 0 ? null : getBuyerRowFromOrderData(buyerOrderResultList.get(0));
		Comparator<Row> comparator = new Comparator<Row>() {

			@Override
			public int compare(Row o1, Row o2) {
				// TODO Auto-generated method stub
				long o2Time = 0L;
				long o1Time = 0L;
				
				try {
					o1Time = o1.get("createtime").valueAsLong();
					o2Time = o2.get("createtime").valueAsLong();
				} catch (TypeException e) {
					// TODO Auto-generated catch block
					System.out.println("Catch type error");
					e.printStackTrace();
				}
				return o2Time - o1Time > 0 ? 1 : -1;
			}

		};
		JoinOne joinResult = new JoinOne(buyerOrderResultList, buyerRow, comparator, "goodid", null);
		return joinResult;
//		return new Iterator<OrderSystem.Result>() {
//
//			PriorityQueue<Row> o = buyerOrderQueue;
//			Row buyerData = o.peek() == null ? null : getBuyerRowFromOrderData(o.peek());
//
//			public boolean hasNext() {
//				return o != null && o.size() > 0;
//			}
//
//			public Result next() {
//				if (!hasNext()) {
////					if (query2Count.get() % CommonConstants.QUERY_PRINT_COUNT ==0) {
////						System.out.println("query2 time:"+ (System.currentTimeMillis() - start));
////					}
//					return null;
//				}
//				Row orderData = buyerOrderQueue.poll();
//				Row goodData = getGoodRowFromOrderData(orderData);
//				return ResultImpl.createResultRow(orderData, buyerData, goodData, null);
//			}
//
//			public void remove() {
//
//			}
//		};
	}
	/**
	 * 用于0或者1次join时的简化操作
	 * @author immortalCockRoach
	 *
	 */
	private class JoinOne implements Iterator<OrderSystem.Result> {
		private List<Row> orderRows;
		private Row fixRow;
		// orderQueue和joinDataQueue的排序比较器
		private Comparator<Row> comparator;
		private PriorityQueue<Row> orderQueue;
		private Map<String, Row> joinDataMap;
		private Set<String> joinDataIndexSet;
		// "buyerid" 或者"goodid" null表示不需要join
		String joinId;
		Collection<String> queryKeys;
		public JoinOne(List<Row> orderRows,Row fixRow,Comparator<Row> comparator, String joinId, Collection<String> queryKeys) {
			this.orderRows = orderRows;
			this.fixRow = fixRow;
			this.comparator = comparator;
			this.joinId = joinId;
			this.queryKeys = queryKeys;
			this.orderQueue = new PriorityQueue<>(512, comparator);
			for (Row orderRow : orderRows) {
				orderQueue.offer(orderRow);
			}
			// 读取不同的good(buyer)Id 查找Row(不论是从cache还是通过memoryMap的索引去原始文件查找)组成一个map供之后join
			// 当orderRow为空或者query3最后得到的tag为order或者good的时候 不需要查询join
			if (joinId != null && orderRows.size() > 0) {
				joinDataIndexSet = new HashSet<>(512);
				joinDataMap = new HashMap<>(512);
				getUniqueDataIndex();
				// 当有没有现成的Row的时候
				if(joinDataIndexSet.size() > 0) {
					traverseOriginalFile();
				}
			}
		}
		/**
		 * 根据orderRow的信息查找出无重复的，且不在cache中的good/buyer的index信息
		 * 如果已经在cache中 直接放入Map
		 */
		private void getUniqueDataIndex() {
			// 首先尽量从cache中取Row 如果没有的话再去memoryMap中查找
			for (Row orderRow : orderRows) {
				String id = orderRow.getKV(joinId).valueAsString();
				// 已经Map包含这个Row了就跳过
				if (!joinDataMap.containsKey(joinId)) {
					String cachedString;
					if (joinId.equals("buyerid")) {
						cachedString = buyersCache.get(id);
					} else {
						cachedString = goodsCache.get(id);
					}
					// 说明cache中有对应id的row信息，直接转换并加入Map
					if (cachedString != null) {
						Row goodData = StringUtils.createKVMapFromLine(cachedString, CommonConstants.SPLITTER);

						joinDataMap.put(id, goodData);
					} else { // cache中不包含时从memoryMap取原始数据的index信息,便于之后查找
						if (!joinDataIndexSet.contains(id)) {
							if (joinId.equals("buyerid")) {
								joinDataIndexSet.add(buyerMemoryIndexMap.get(id));
							} else {
								joinDataIndexSet.add(goodMemoryIndexMap.get(id));
							}
						}
					}
				}
			}
		}
		
		private void traverseOriginalFile() {
			// 此时得到的joinDataIndexSet 为无重复的good/buyer的index信息
			Map<String,PriorityQueue<String[]>> originalDataAccessSequence = createOrderDataAccessSequence(joinDataIndexSet);
			for (Map.Entry<String, PriorityQueue<String[]>> e : originalDataAccessSequence.entrySet()) {
				String file = null;
				if (joinId.equals("buyerid")) {
					file = buyerFiles.get(Integer.parseInt(e.getKey()));
				} else {
					file = goodFiles.get(Integer.parseInt(e.getKey()));
				}
//				System.out.println("file:"+file);
				String[] sequence;
				String line;
				try(RandomAccessFile orderFileReader = new RandomAccessFile(file, "r")) {
					sequence = e.getValue().poll();
					while(sequence != null) {
						
						Long offset = Long.parseLong(sequence[0]);

						byte[] content = new byte[Integer.valueOf(sequence[1])];
						orderFileReader.seek(offset);
						orderFileReader.read(content);
						line = new String(content);

						Row kvMap = StringUtils.createKVMapFromLine(line, CommonConstants.SPLITTER);
						if (joinId.equals("buyerid")) {
							String id = kvMap.getKV("buyerid").valueAsString();
							joinDataMap.put(id, kvMap);
							buyersCache.put(id, line);
						} else {
							String id = kvMap.getKV("goodid").valueAsString();
							joinDataMap.put(id, kvMap);
							goodsCache.put(id, line);
						}
						sequence = e.getValue().poll();
					}
					
				} catch (IOException e1) {

				} 	
			}	
		}
		
		@Override
		public boolean hasNext() {

			return orderQueue != null && orderQueue.size() > 0;
		}

		@Override
		public Result next() {
			if (!hasNext()) {
				return null;
			}
			// 不需要join的时候直接create
			if (joinId == null){
				return ResultImpl.createResultRow(orderQueue.poll(), fixRow, null, createQueryKeys(queryKeys));
			} else {
				// 需要join的时候从Map中拉取
				Row orderRow = orderQueue.poll();
				Row dataRow = joinDataMap.get(orderRow.getKV(joinId).valueAsString());
				return ResultImpl.createResultRow(orderRow, fixRow, dataRow, createQueryKeys(queryKeys));
			}	
		}

		@Override
		public void remove() {
			// TODO Auto-generated method stub	
		}
	}
	
	private class OrderDataSearch implements Callable<List<Row>> {
		
		private int fileIndex;
		private PriorityQueue<String[]> sequenceQueue;
		
		public OrderDataSearch(int fileIndex, PriorityQueue<String[]> sequenceQueue) {
			this.fileIndex = fileIndex;
			this.sequenceQueue = sequenceQueue;
		}
		@Override
		public List<Row> call() throws Exception {
			// 这个sequence不可能是负数
			List<Row> result = new ArrayList<>(sequenceQueue.size());
			String file = orderFiles.get(fileIndex);
//			System.out.println("file:"+file);
			String[] sequence;
			try(RandomAccessFile orderFileReader = new RandomAccessFile(file, "r")) {
				sequence = sequenceQueue.poll();
				while(sequence != null) {
					
					Long offset = Long.parseLong(sequence[0]);
//					System.out.println("offset:"+offset);
//					System.out.println("lenth:"+Integer.valueOf(sequence[1]));
					byte[] content = new byte[Integer.valueOf(sequence[1])];
					orderFileReader.seek(offset);
					orderFileReader.read(content);
					String line = new String(content);

					Row kvMap = StringUtils.createKVMapFromLine(line, CommonConstants.SPLITTER);
					// buyer的话需要join
					result.add(kvMap);
//					salerGoodsQueue.offer(kvMap);
					sequence = sequenceQueue.poll();
				}
				
			} 	
			return result;
		}
		
	}

	public Iterator<Result> queryOrdersBySaler(String salerid, String goodid, Collection<String> keys) {
		while (this.isConstructed == false) {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
//		final long start = System.currentTimeMillis();
//		int count = query3Count.incrementAndGet();
//		if (count % CommonConstants.QUERY_PRINT_COUNT == 0) {
//			System.out.println("query3 count:" + query3Count.get());
//		}
		String tag = keys != null && keys.size() == 1 ? getKeyJoin((String)keys.toArray()[0]): "all";
//		final PriorityQueue<Row> salerGoodsQueue = new PriorityQueue<>(512, new Comparator<Row>() {
//
//			@Override
//			public int compare(Row o1, Row o2) {
//				// TODO Auto-generated method stub
//				long o2Time;
//				long o1Time;
//				o1Time = o1.get("orderid").longValue;
//				o2Time = o2.get("orderid").longValue;
//				return o1Time - o2Time > 0 ? 1 : -1;
//			}
//
//		});
		List<Row> salerGoodsList = new ArrayList<>(512);
		final Collection<String> queryKeys = keys;

		int index = HashUtil.indexFor(HashUtil.hashWithDistrub(goodid), CommonConstants.QUERY3_ORDER_SPLIT_SIZE);
		String indexFile = this.query3Path + File.separator + index + CommonConstants.INDEX_SUFFIX;
//			cachedStrings = new ArrayList<>(100);
		List<String> offsetRecords = new ArrayList<>(512);
		try (ExtendBufferedReader indexFileReader = IOUtils.createReader(indexFile, CommonConstants.INDEX_BLOCK_SIZE)){
			String line = indexFileReader.readLine();
			
			while (line != null) {
				// 获得一行中以<goodid>开头的行
//					offsetRecords.addAll(StringUtils.createListFromLongLineWithKey(line, goodid, CommonConstants.SPLITTER));
				if (line.startsWith(goodid)) {
					int p = line.indexOf(':');
					offsetRecords.add(line.substring(p + 1));
				}
				line = indexFileReader.readLine();
			}
//			if (count % CommonConstants.QUERY_PRINT_COUNT == 0) {
//				System.out.println("query3 index time:" + (System.currentTimeMillis() - start) + "size:" +offsetRecords.size());
//			}
			
			if (offsetRecords.size() > 0 ) {
//				System.out.println(offsetRecords.size());
//				Row kvMap;
				Map<String,PriorityQueue<String[]>> buyerOrderAccessSequence = createOrderDataAccessSequence(offsetRecords);
				List<Future<List<Row>>> result = new ArrayList<>();
				for (Map.Entry<String, PriorityQueue<String[]>> e : buyerOrderAccessSequence.entrySet()) {
//					String file = this.orderFiles.get(Integer.parseInt(e.getKey()));
////					System.out.println("file:"+file);
//					String[] sequence;
//					try(RandomAccessFile orderFileReader = new RandomAccessFile(file, "r")) {
//						sequence = e.getValue().poll();
//						while(sequence != null) {
//							
//							Long offset = Long.parseLong(sequence[0]);
////							System.out.println("offset:"+offset);
////							System.out.println("lenth:"+Integer.valueOf(sequence[1]));
//							byte[] content = new byte[Integer.valueOf(sequence[1])];
//							orderFileReader.seek(offset);
//							orderFileReader.read(content);
//							line = new String(content);
//	
//							kvMap = StringUtils.createKVMapFromLine(line, CommonConstants.SPLITTER);
//							// buyer的话需要join
//							salerGoodsList.add(kvMap);
////							salerGoodsQueue.offer(kvMap);
//							sequence = e.getValue().poll();
//						}
//						
//					} 
					int fileIndex = Integer.parseInt(e.getKey());
					OrderDataSearch search = new OrderDataSearch(fileIndex, e.getValue());
					result.add(multiQueryPool3.submit(search));
				}
				for (Future<List<Row>> f: result) {
					try {
						List<Row> list = f.get();
						if (list != null) {
							for(Row row : list) {
								salerGoodsList.add(row);
							}
						}
					} catch (InterruptedException | ExecutionException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
				}

//				if (count % CommonConstants.QUERY_PRINT_COUNT == 0) {
//					System.out.println("query3 original data time:" + (System.currentTimeMillis() - start) + "size:" + buyerOrderAccessSequence.size());
//				}
			} else {
				System.out.println("query3 can't find order:");
			}

		} catch (IOException e) {
			
		}
		
		// query3至多只需要join buyer信息
		Row goodRow = salerGoodsList.size() == 0 ? null : getGoodRowFromOrderData(salerGoodsList.get(0));
		Comparator<Row> comparator = new Comparator<Row>() {

			@Override
			public int compare(Row o1, Row o2) {
				// TODO Auto-generated method stub
				long o2Time = 0L;
				long o1Time = 0L;			
				try {
					o1Time = o1.get("orderid").valueAsLong();
					o2Time = o2.get("orderid").valueAsLong();
				} catch (TypeException e) {
					// TODO Auto-generated catch block
					System.out.println("Catch type error");
					e.printStackTrace();
				}
				return o1Time - o2Time > 0 ? 1 : -1;
			}

		};
//		 查询3可能不需要join的buyer
		String joinTable = tag.equals("buyer") || tag.equals("all") ? "buyerid" : null;
		JoinOne joinResult = new JoinOne(salerGoodsList, goodRow, comparator, joinTable, createQueryKeys(queryKeys));
//		if (count % CommonConstants.QUERY_PRINT_COUNT == 0) {
//			System.out.println("query3 join time:" + (System.currentTimeMillis() - start));
//		}
		return joinResult;
//		return new Iterator<OrderSystem.Result>() {
//
//			final PriorityQueue<Row> o = salerGoodsQueue;
//			// 使用orderData(任意一个都对应同一个good信息)去查找对应的goodData
//			final Row goodData = o.peek() == null ? null : getGoodRowFromOrderData(o.peek());
//			final String tag = queryKeys != null && queryKeys.size() == 1 ? getKeyJoin((String)(queryKeys.toArray()[0])) : "all";
//			public boolean hasNext() {
//				return o != null && o.size() > 0;
//			}
//
//			public Result next() {
//				if (!hasNext()) {
//
//					return null;
//				}
//				Row orderData = o.poll();
//				Row buyerData = null;
//				// 当时all或者buyer的时候才去join buyer
//				if (tag.equals("buyer") || tag.equals("all")) {
////					System.out.println("join");
//					buyerData = getBuyerRowFromOrderData(orderData);
//				}
//				// 由于此时不需要join buyer 因此为null
//				return ResultImpl.createResultRow(orderData, buyerData, goodData, createQueryKeys(queryKeys));
//			}
//
//			public void remove() {
//				// ignore
//			}
//		};
	}

	public KeyValue sumOrdersByGood(String goodid, String key) {
		while (this.isConstructed == false) {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
//		long start = System.currentTimeMillis();
//		int count = query4Count.incrementAndGet();
//		if (count % CommonConstants.QUERY_PRINT_COUNT == 0) {
//			System.out.println("query4 count:" + query4Count.get());	
//		}
		// 快速处理 减少不必要的查询的join开销
		String tag = "all";
		// 当为good表字段时快速处理
		if (key.equals("price") || key.equals("offprice") || key.startsWith("a_g_")) {
			return getGoodSumFromGood(goodid, key);
		} else if (key.startsWith("a_b_")) { //表示需要join buyer和order
			tag = "buyer";
		} else if (key.equals("amount") || key.startsWith("a_o_")) { //表示只需要order数据
			tag = "order";
		} else {
			// 不可求和的字段直接返回
			return null;
		}
		
		List<Row> ordersData = new ArrayList<>(512);
		

		int index = HashUtil.indexFor(HashUtil.hashWithDistrub(goodid), CommonConstants.QUERY3_ORDER_SPLIT_SIZE);
		String indexFile = this.query3Path + File.separator + index + CommonConstants.INDEX_SUFFIX;
//			cachedStrings = new ArrayList<>(100);
		List<String> offsetRecords = new ArrayList<>(512);
		try(ExtendBufferedReader indexFileReader = IOUtils.createReader(indexFile, CommonConstants.INDEX_BLOCK_SIZE)){
			String line = indexFileReader.readLine();
			
			while (line != null) {
				// 获得一行中以<goodid>开头的行
//					offsetRecords.addAll(StringUtils.createListFromLongLineWithKey(line, goodid, CommonConstants.SPLITTER));
				if (line.startsWith(goodid)) {
					int p = line.indexOf(':');
					offsetRecords.add(line.substring(p + 1));
				}
				line = indexFileReader.readLine();
			}
//			if (count % CommonConstants.QUERY_PRINT_COUNT ==0) {
//				System.out.println("query4 index time:"+ (System.currentTimeMillis() - start) + "size:" + offsetRecords.size());
//			}
			
			if (offsetRecords.size() > 0 ) {
//				System.out.println(offsetRecords.size());
//				Row kvMap;
				Map<String,PriorityQueue<String[]>> buyerOrderAccessSequence = createOrderDataAccessSequence(offsetRecords);
				List<Future<List<Row>>> result = new ArrayList<>();
				for (Map.Entry<String, PriorityQueue<String[]>> e : buyerOrderAccessSequence.entrySet()) {
//					String file = this.orderFiles.get(Integer.parseInt(e.getKey()));
////					System.out.println("file:"+file);
//					String[] sequence;
//					try(RandomAccessFile orderFileReader = new RandomAccessFile(file, "r")) {
//						sequence = e.getValue().poll();
//						while(sequence != null) {
//							
//							Long offset = Long.parseLong(sequence[0]);
//
//							byte[] content = new byte[Integer.valueOf(sequence[1])];
//							orderFileReader.seek(offset);
//							orderFileReader.read(content);
//							line = new String(content);
//	
//							kvMap = StringUtils.createKVMapFromLine(line, CommonConstants.SPLITTER);
//							ordersData.add(kvMap);
//							sequence = e.getValue().poll();
//						}
//						
//					}
					int fileIndex = Integer.parseInt(e.getKey());
					OrderDataSearch search = new OrderDataSearch(fileIndex, e.getValue());
					result.add(multiQueryPool4.submit(search));
				}
				for (Future<List<Row>> f: result) {
					try {
						List<Row> list = f.get();
						if (list != null) {
							for(Row row : list) {
								ordersData.add(row);
							}
						}
					} catch (InterruptedException | ExecutionException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
				}

//				if (count % CommonConstants.QUERY_PRINT_COUNT ==0) {
//					System.out.println("query4 original time:"+ (System.currentTimeMillis() - start) + "size:" + buyerOrderAccessSequence.size());
//				}
			} else {
				System.out.println("query4 can't find order:");
			}
			
		} catch (IOException e) {
			
		}

		// 如果不存在对应的order 直接返回null
		if (ordersData.size() == 0) {
			return null;
		}
		HashSet<String> queryingKeys = new HashSet<String>();
		queryingKeys.add(key);
		
		List<Result> allData = new ArrayList<>(ordersData.size());
		// query4至多只需要join buyer信息
		if(tag.equals("order")) { // 说明此时只有orderData 不需要join
			for (Row orderData : ordersData) {
//				Row buyerData = null;
//				if (tag.equals("buyer") || tag.equals("all")) {
//					buyerData = getBuyerRowFromOrderData(orderData);
//				}
				allData.add(ResultImpl.createResultRow(orderData, null, null, queryingKeys));
			}
		} else { // buyer或者all
			Comparator<Row> comparator = new Comparator<Row>() {

				@Override
				public int compare(Row o1, Row o2) {
					// TODO Auto-generated method stub
					long o2Time = 0L;
					long o1Time = 0L;			
					try {
						o1Time = o1.get("orderid").valueAsLong();
						o2Time = o2.get("orderid").valueAsLong();
					} catch (TypeException e) {
						// TODO Auto-generated catch block
						System.out.println("Catch type error");
						e.printStackTrace();
					}
					return o1Time - o2Time > 0 ? 1 : -1;
				}

			};
			// 不需要join good信息
			JoinOne joinResult = new JoinOne(ordersData, null, comparator, "buyerid", queryingKeys);

			while (joinResult.hasNext()) {
				allData.add(joinResult.next());
			}
//			if (count % CommonConstants.QUERY_PRINT_COUNT == 0) {
//				System.out.println("query4 join time:" + (System.currentTimeMillis() - start));
//			}
		}

//		if (count % CommonConstants.QUERY_PRINT_COUNT ==0) {
//			System.out.println("query4 all time:"+ (System.currentTimeMillis() - start));
//		}
		// accumulate as Long
		try {
			boolean hasValidData = false;
			long sum = 0;
			for (Result r : allData) {
				KeyValue kv = r.get(key);
				if (kv != null) {
					sum += kv.valueAsLong();
					hasValidData = true;
				}
			}
			if (hasValidData) {
				return new KV(key, Long.toString(sum));
			}
		} catch (TypeException e) {
		}

		// accumulate as double
		try {
			boolean hasValidData = false;
			double sum = 0;
			for (Result r : allData) {
				KeyValue kv = r.get(key);
				if (kv != null) {
					sum += kv.valueAsDouble();
					hasValidData = true;
				}
			}
			if (hasValidData) {
				return new KV(key, Double.toString(sum));
			}
		} catch (TypeException e) {
		}

		return null;
	}
	
	/**
	 * 当good不存在这个属性的时候，直接返回null
	 * 否则计算出good的Order数 然后相乘并返回即可
	 * @param goodId
	 * @param key
	 * @return
	 */
	private KeyValue getGoodSumFromGood(String goodId, String key) {
		int index = HashUtil.indexFor(HashUtil.hashWithDistrub(goodId), CommonConstants.QUERY3_ORDER_SPLIT_SIZE);
		Row orderData = new Row();
		orderData.putKV("goodid", goodId);
		// 去good的indexFile中查找goodid对应的全部信息
		Row goodData = getGoodRowFromOrderData(orderData);
		// 说明goodid对应的good就不存在 直接返回null
		if (goodData == null) {
			return null;
		}
		// 说明good记录没有这个字段或者没有这个 直接返回null
		KV kv = goodData.get(key);
		if (kv == null) {
			return null;
		}
		// 说明这个rawvalue不是long也不是double
		Object value = StringUtils.parseStringToNumber(kv.valueAsString());
		if (value == null) {
			return null;
		}
		String indexFile = this.query3Path + File.separator + index + CommonConstants.INDEX_SUFFIX;
		int count = 0;
		// 此处只是统计goodId对应的order的个数
		try(ExtendBufferedReader indexFileReader = IOUtils.createReader(indexFile, CommonConstants.INDEX_BLOCK_SIZE)){
			String line = indexFileReader.readLine();
			
			while (line != null) {
				
				if (line.startsWith(goodId)) {
					// 表示数量+1
					count++;
				}
				line = indexFileReader.readLine();
			}
		} catch (IOException e) {
			
		}
		// 判断具体类型 相乘并返回
		if (value instanceof Long) {
			Long result = ((long)value) * count;
			return new KV(key, Long.toString(result));
		} else {
			Double result = ((double)value) * count;
			return new KV(key, Double.toString(result));
		}
	}
	
}
