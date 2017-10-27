
@Controller
@RequestMapping(value = "web/shop/pay", produces = "application/json;charset=utf-8")
public class ShopPayController extends AbstractController {

	@Autowired
	@Qualifier("serviceProviderShopServiceImpl")
	private ServiceProviderShopService serviceProviderShopService;

	@Autowired
	private ShopService shopService;
	
	@Autowired
	@Qualifier("payServiceImpl")
	private PayService payService;
	
	@Value("${dmm-web.oss.temp.path:temp}")
	private String tempPath;

	@Autowired
	private DOssClient ossClient;
	
	
	private static Map<String, String> ORDER_FIELD_MAP = new HashMap<>();
	static {
		ORDER_FIELD_MAP.put("ownerName", ShopBaseInfo.Queries.OWNER_NAME);
		ORDER_FIELD_MAP.put("name", ShopBaseInfo.Queries.NAME);
		ORDER_FIELD_MAP.put("ownerMobile", ShopBaseInfo.Queries.OWNER_MOBILE);
		ORDER_FIELD_MAP.put("enabledTime", ServiceProviderShop.Queries.CREATED);
		ORDER_FIELD_MAP.put("lastModify", ServiceProviderShop.Queries.LAST_MODIFIED);
	}

	/**
	 * 服务商查询服务关系的门店 serviceprovidershop.service=1
	 * 
	 * @param query
	 * @return
	 * @throws ParseException 
	 */
	@ApiOperation(value = "“服务关系”门店的查询", notes = "服务商查询关系为“服务关系”的门店")
	@RequestMapping(value = "query", method = RequestMethod.POST)
	public @ResponseBody ExtResponse<List<BServiceProviderShop>> query(
			@ApiParam(value = "start,limit,filters[ownerName:%=%, shopName:%=%, ownerMobile:%=%],enabledTime:[,](yyyy-MM-dd)]") @RequestBody ListObjectRequest query) throws ParseException {

		// 创建返回体
		ExtResponse<List<BServiceProviderShop>> response = new ExtResponse<List<BServiceProviderShop>>();

		// 创建门店筛选条件
		QueryDefinition qd;
          qd = createShopQueryDefinition(query);
		// 组装门店ID数组用于后续serviceprovideshop表的查询
		List<String> shopIds = new ArrayList<String>();
		// 创建索引门店的map
		Map<String, ShopBaseInfo> shopMap = new HashMap<String, ShopBaseInfo>();
		if (qd != null) {
			// 查询门店
			QueryResult<ShopBaseInfo> shopResult = shopService.queryBase(qd);
			// 查询门店结果为空
			if (shopResult.getRecords() == null || shopResult.getRecords().size() == 0)
				return response;

			for (ShopBaseInfo s : shopResult.getRecords()) {
				shopIds.add(s.getUuid());
				shopMap.put(s.getUuid(), s);
			}
		}

		// 向查询请求中注入关系为“服务关系”的限制
		query.getFilters().add(new FilterObjectParam("service", Service.PAYMENT.getVal()));
		// 创建服务商门店关系查询条件
		QueryDefinition qd2 = createServiceProivderShopQueryDefinition(query, shopIds);
		QueryResult<ServiceProviderShop> serviceProviderShopResult = serviceProviderShopService.query(qd2);

		// 如果门店索引map为空，则将serviceprovideshop的门店信息查询出来
		if (shopMap.isEmpty()) {
			for (ServiceProviderShop s : serviceProviderShopResult.getRecords()) {
				shopIds.add(s.getShop());
			}
			shopMap = shopService.getBaseInfos(shopIds);
		}
		List<BServiceProviderShop> data = ConverterUtil.convert(serviceProviderShopResult.getRecords(), new ServiceProviderShopToB(shopMap));
		response.setData(data);
	    response.setTotal(serviceProviderShopResult.getRecordCount());
		return response;
	}

	/**
	 * 服务商根据授权码与门店建立服务关系 serviceprovidershop.service=1
	 * 
	 * @param payJoinCode
	 * @return
	 */
	@ApiOperation(value = "创建“服务关系”的门店", notes = "服务商创建关系为“服务关系”的门店")
	@RequestMapping(value = "create", method = RequestMethod.GET)
	public @ResponseBody ExtResponse<Object> create(@ApiParam(value = "门店授权码") @RequestParam("payJoinCode") String payJoinCode) {
		// 创建返回体
		ExtResponse<Object> response = new ExtResponse<Object>();
		
		// 0.授权码验证与解析
		Assert.assertArgumentNotNull(payJoinCode, "payJoinCode");
		boolean matchFlag = Pattern.matches("j=[a-z0-9]+&\\d+", payJoinCode);
		if(!matchFlag) {
			response.setSuccess(false);
			List<String> errors = new ArrayList<String>();
			errors.add("无效的授权码, 格式有误!");
			response.setMessage(errors);
			return response;
		}
		
		//调用接口
		List<String> messages = new ArrayList<String>();
		try {
			payService.create(payJoinCode, getOperationContext());
			response.setSuccess(true);
			messages.add("成功添加门店");
		} catch (DmmException e) {
			response.setSuccess(false);
			messages.add(e.getMessage());
		} catch (AuthenticationException e) {
			response.setSuccess(false);
			messages.add(e.getMessage());
		}
		
		response.setMessage(messages);
		return response;
	}

	  @ApiOperation(value = "商户搜索导出Excel")
	  @RequestMapping(value = "export", method = RequestMethod.POST)
	  public @ResponseBody ExtResponse<String> export(
	      HttpServletRequest request,
	      @ApiParam(value = "start,limit,filters[ownerName:%=%, shopName:%=%, ownerMobile:%=%],enabledTime:[,](yyyy-MM-dd)]") @RequestBody ListObjectRequest query)
	      throws Exception {
	    ExtResponse<String> response = new ExtResponse<>();
	    ExtResponse<List<BServiceProviderShop>>  data = this.query(query);
	    if (data.getData() == null || data.getData().isEmpty()) {
	      response.setSuccess(Boolean.FALSE);
	      response.setMessage(Arrays.asList("要导出的数据为空"));
	      response.setTotal(0);
	      return response;
	    }
	    String url = exportExcel(buildWorkbook(data.getData(), getShopHeader()));
	    response.setData(url);
	    response.setTotal(data.getTotal());
	    return response;
	  }
	  
	  private String[] getShopHeader() {
	    String[] header = {
	        "门店全称", //
	        "门店ID", //
	        "门店所在地址", //
	        "店主姓名",
	        "店主手机号",
	        "关联门店的时间"};
	    return header;
	  }
	  
	  private XSSFWorkbook buildWorkbook(List<BServiceProviderShop> data, String[] titles) {
	    XSSFWorkbook workBook = new XSSFWorkbook();
	    // 在workbook中添加一个sheet,对应Excel文件中的sheet
	    XSSFSheet sheet = workBook.createSheet("sheet1");
	    // 构建表头
	    XSSFRow headRow = sheet.createRow(0);
	    XSSFCell cell = null;
	    for (int i = 0; i < titles.length; i++) {
	      cell = headRow.createCell(i);
	      cell.setCellValue(titles[i]);
	    }
	    // 构建表体数据
	    for (int i = 0; i < data.size(); i++) {
	      BServiceProviderShop shop = data.get(i);
	      XSSFRow bodyRow = sheet.createRow(i + 1);
	      cell = bodyRow.createCell(0);
	      cell.setCellValue(shop.getFullName());
	      
	      cell = bodyRow.createCell(1);
	      cell.setCellValue(shop.getShop());
	      
	      cell = bodyRow.createCell(2);
          cell.setCellValue(shop.getAddress());

          cell = bodyRow.createCell(3);
          cell.setCellValue(shop.getOwnerName());
          
          cell = bodyRow.createCell(4);
          cell.setCellValue(shop.getOwnerMobile());
          
          cell = bodyRow.createCell(5);
          cell.setCellValue(String.format("%tF", shop.getEnabledTime())+" "+String.format("%tT", shop.getEnabledTime()));
          
	    }
	    return workBook;
	  }
	  
	  private String exportExcel(XSSFWorkbook workbook) throws IOException, DOssException {
	    String fileName = DateUtil.format(new Date(), DateUtil.YYYYMMDD) + "门店资料";
	    File f = File.createTempFile(fileName, ".xlsx");
	    try {
	      OutputStream out = new FileOutputStream(f);
	      workbook.write(out);
	      out.close();
	    } catch (FileNotFoundException e) {
	      e.printStackTrace();
	    }

	    if (!tempPath.endsWith("/")) {
	      tempPath = tempPath + "/";
	    }
	    DOssObject object = ossClient.putObject(tempPath, f.getName(), new FileInputStream(f),
	        Boolean.FALSE);
	    f.delete();
	    return object.getObjectUrl();
	  }
	
	private QueryDefinition createShopQueryDefinition(ListObjectRequest request) {
		Boolean search = Boolean.FALSE;
		QueryDefinition qd = new QueryDefinition();
		AndCondition and = new AndCondition();
		for (FilterObjectParam fp : request.getFilters()) {
			String property = fp.getProperty();
			Object value = fp.getValue();
			if (!checkValue(value))
				continue;
			if ("ownerName:%=%".equals(property)) {
				search = Boolean.TRUE;
				and.addCondition2(QueryCondition.DUMMY_FIELD, ShopBaseInfo.Queries.OWNER_NAME_LIKE, value);
			} else if ("shopName:%=%".equals(property)) {
				search = Boolean.TRUE;
				and.addCondition2(ShopBaseInfo.Queries.NAME, Cop.LIKES, value);
			} else if ("ownerMobile:%=%".equals(property)) {
				search = Boolean.TRUE;
				and.addCondition2(QueryCondition.DUMMY_FIELD, ShopBaseInfo.Queries.OWNER_MOBILE_LIKE, value);
			} else if ("shopId".equals(property)) {
				search = Boolean.TRUE;
				and.addCondition2(ShopBaseInfo.Queries.UUID, Cop.EQUALS, value);
			}
		}
	
		if (!search)
			return null;
	
		qd.setCondition(and);
		return qd;
	}

	/**
	 * 拼接查询条件
	 * 
	 * @param conditions
	 * @return
	 * @throws ParseException 
	 * @throws AuthenticationException
	 * @throws IllegalStateException
	 */
	private QueryDefinition createServiceProivderShopQueryDefinition(ListObjectRequest request, List<String> shopIds) throws ParseException {
		QueryDefinition qd = new QueryDefinition(ControllerHelper.toPage(request.getStart(), request.getLimit()),
				request.getLimit());
		AndCondition ad = new AndCondition();

		ad.addCondition2(ServiceProviderShop.Queries.SERVICE_PROVIDER, Cop.EQUALS, getServiceProviderId());

		for (FilterObjectParam filter : request.getFilters()) {
			String property = filter.getProperty();
			Object value = filter.getValue();

			if (!checkValue(value))
				continue;

			if ("service".equals(property))
				ad.addCondition2(ServiceProviderShop.Queries.SERVICE, Cop.EQUALS, value);
	         else if ("enabledTime:[,]".equals(property)) {
	              String[] data = null;
	              if (value instanceof Collection) {
	                Collection c = (Collection) value;
	                data = (String[]) c.toArray(new String[0]);
	              } else if (value instanceof String) {
	                data = ((String) value).split(",");
	              }
	              Date start = null;
	              Date end = null;
	              if (data.length > 0) {
	                if (StringUtils.isNotBlank(data[0])) {
	                  start = this.DateFormatToFullTime(data[0], Boolean.FALSE);
	                  ad.addCondition2(ServiceProviderShop.Queries.CREATED, Cop.GREATER_OR_EQUALS, start);
	                }
	              }
	              if (data.length == 2) {
	                if (StringUtils.isNotBlank(data[1])) {
	                  end = this.DateFormatToFullTime(data[1], Boolean.TRUE);
	                  ad.addCondition2(ServiceProviderShop.Queries.CREATED, Cop.LESS, end);
	                }
	              }
	            }
		}

		if (shopIds != null && !shopIds.isEmpty())
			ad.addCondition2(ServiceProviderShop.Queries.SHOP, Cop.IN, shopIds.toArray());

		qd.setCondition(ad);
		qd.getOrders().addAll(ControllerHelper.toOrders(request.getSorters(), ORDER_FIELD_MAP));
		if (qd.getOrders().isEmpty())
			qd.addOrder(ServiceProviderShop.Queries.CREATED, QueryOrderDirection.desc);

		return qd;
	}
	
}
