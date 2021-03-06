package com.gooddeep.dev.elasticsearch.commons.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.highlight.HighlightBuilder;
import org.elasticsearch.search.highlight.HighlightBuilder.Field;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.SearchResultMapper;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.DeleteQuery;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.core.query.SearchQuery;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.stereotype.Component;

import com.gooddeep.dev.core.helper.PropertyHelper;
import com.gooddeep.dev.core.helper.UuidHelper;
import com.gooddeep.dev.core.model.BasePage;
import com.gooddeep.dev.elasticsearch.commons.model.EsBaseBean;
import com.gooddeep.dev.elasticsearch.commons.service.EsBaseService;

@Component("esBaseDao")
public abstract class EsBaseDaoImpl<T> implements EsBaseDao<T> {

	private Logger logger = LoggerFactory.getLogger(EsBaseService.class);

	@Autowired
	private ElasticsearchTemplate elasticsearchTemplate;

	@Autowired
	private Client esClient;

	
	/**
	 * 插入或等新，需要有id，id需要自己生成
	 * 
	 * @param tList
	 * @return
	 */
	public boolean insertOrUpdate(List<T> tList) {
		List<IndexQuery> queries = new ArrayList<IndexQuery>();
		for (T t : tList) {
			String id = ((EsBaseBean) t).getId();
			if (id == null) {
				id = UuidHelper.getRandomUUID();
				((EsBaseBean) t).setId(id);
			}
			IndexQuery indexQuery = new IndexQueryBuilder().withId(id).withObject(t).build();
			queries.add(indexQuery);
		}
		elasticsearchTemplate.bulkIndex(queries);
		return true;
	}

	/**
	 * 插入或更新
	 * 
	 * @param t
	 * @return
	 */
	public boolean insertOrUpdate(T t) {

		String id = ((EsBaseBean) t).getId();
		if (id == null) {
			id = UuidHelper.getRandomUUID();
			((EsBaseBean) t).setId(id);
		}
		try {
			IndexQuery indexQuery = new IndexQueryBuilder().withId(id).withObject(t).build();
			elasticsearchTemplate.index(indexQuery);
			return true;
		} catch (Exception e) {
			logger.error("insert or update user info error.", e);
			return false;
		}
	}

	/**
	 * 删除
	 * 
	 * @param id
	 * @return
	 */
	public boolean deleteById(String id) {
		try {
			elasticsearchTemplate.delete(getEntityClass(), id);
			return true;
		} catch (Exception e) {
			logger.error("delete " + getEntityClass() + " by id " + id
					+ " error.", e);
			return false;
		}
	}
	
	/**
	 * 删除ids
	 * @param idList
	 * @return
	 */
	@Override
	public boolean deleteByIds(List<String> idList) {
		try {
			 CriteriaQuery criteriaQuery = new CriteriaQuery(new Criteria());
			 criteriaQuery.setIds(idList);
			 elasticsearchTemplate.delete(criteriaQuery, getEntityClass());
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}


	/**
	 * 根据条件查询
	 * @param filedContentMap 不能为null
	 * @return
	 */
	public boolean deleteByQuery(Map<String,Object> filedContentMap) {
		try {
			DeleteQuery dq = new DeleteQuery();
			
			BoolQueryBuilder qb=QueryBuilders. boolQuery();
			if(filedContentMap!=null)
				for (String key : filedContentMap.keySet()) {//字段查询
					qb.must(QueryBuilders.matchQuery(key,filedContentMap.get(key)));
				}
			dq.setQuery(qb);;
			elasticsearchTemplate.delete(dq, getEntityClass());;
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}
	/**
	 * 检查健康状态
	 * 
	 * @return
	 */
	public boolean ping() {
		try {
			ActionFuture<ClusterHealthResponse> health = esClient.admin()
					.cluster().health(new ClusterHealthRequest());
			ClusterHealthStatus status = health.actionGet().getStatus();
			if (status.value() == ClusterHealthStatus.RED.value()) {
				throw new RuntimeException(
						"elasticsearch cluster health status is red.");
			}
			return true;
		} catch (Exception e) {
			logger.error("ping elasticsearch error.", e);
			return false;
		}
	}

	/**
	 * 条件查询
	 * 
	 * @param searchfields
	 *            查询字段
	 * @param filedContentMap
	 *            字段和查询内容
	 * @param sortField
	 *            排序 字段
	 * @param order
	 *            排序
	 * @param from
	 * @param size
	 * @return
	 */
	@Override
	public BasePage<T> queryPage(Map<String,Object> filedContentMap, final List<String> heightFields, String sortField, SortOrder order, BasePage<T>basePage) {
		
		Field[] hfields=new Field[0];
		if(heightFields!=null)
		{
			hfields = new Field[heightFields.size()];
			for (int i = 0; i < heightFields.size(); i++) {
				hfields[i] = new HighlightBuilder.Field(heightFields.get(i)).preTags("<em style='color:red'>").postTags("</em>").fragmentSize(250);
			}
		}
		NativeSearchQueryBuilder nsb = new NativeSearchQueryBuilder().withHighlightFields(hfields);//高亮字段
		if (sortField != null && order != null)//排序
			nsb.withSort(new FieldSortBuilder(sortField).ignoreUnmapped(true).order(order));
		if (basePage != null)//分页
			nsb.withPageable(new PageRequest(basePage.getPageNo()-1, basePage.getPageSize()));
		BoolQueryBuilder qb=QueryBuilders. boolQuery();
		for (String key : filedContentMap.keySet()) {//字段查询
			qb.must(QueryBuilders.matchQuery(key,filedContentMap.get(key)));
			
		}
		//userKey=78e48b85e94911e0d285f4eec990d556
		//fa6e9c5bb24a21807c59e5fd3b609e12
		nsb.withQuery(qb);
		SearchQuery searchQuery = nsb.build();//查询建立


		Page<T> page = null;
		if (heightFields!=null&&heightFields.size() > 0) {//如果设置高亮
			page = elasticsearchTemplate.queryForPage(searchQuery,
					getEntityClass(), new SearchResultMapper() {
						@SuppressWarnings("unchecked")
						@Override
						public <T> Page<T> mapResults(SearchResponse response,Class<T> clazz, Pageable pageable) {
							List<T> chunk = new ArrayList<T>();
							for (SearchHit searchHit : response.getHits()) {
								if (response.getHits().getHits().length <= 0) {
									return null;
								}

								Map<String, Object> entityMap = searchHit.getSource();
								for (String highName : heightFields) {
									Text text[]=searchHit.getHighlightFields().get(highName).fragments();
									if(text.length>0)
									{
										String highValue = searchHit.getHighlightFields().get(highName).fragments()[0].toString();
										entityMap.put(highName, highValue);
									}
								}
								chunk.add((T) PropertyHelper.getFansheObj(
										getEntityClass(), entityMap));
							}
							if (chunk.size() > 0) {
								return new PageImpl<T>((List<T>) chunk);
							}
							return new PageImpl<T>(new ArrayList<T>());
						}

					});
		} else//如果不设置高亮
		{
		    logger.info("#################"+qb.toString());
		 
			page = elasticsearchTemplate.queryForPage(searchQuery,getEntityClass());
		}
		

	//	List<T> ts = page.getContent();

		basePage.setTotalRecord(page.getTotalElements());
		basePage.setResults(page.getContent());
		return basePage;
	}

	
	@Override
	public List<T> queryList(Map<String, Object> filedContentMap,final List<String> heightFields, String sortField, SortOrder order) {
		Field[] hfields=new Field[0];
		if(heightFields!=null)
		{
			hfields = new Field[heightFields.size()];
			for (int i = 0; i < heightFields.size(); i++) {
				//String o="{\"abc\" : \"[abc]\"}";
				hfields[i] = new HighlightBuilder.Field(heightFields.get(i)).preTags("<em>").postTags("</em>").fragmentSize(250);
			}
		}
		NativeSearchQueryBuilder nsb = new NativeSearchQueryBuilder().withHighlightFields(hfields);//高亮字段
		if (sortField != null && order != null)//排序
			nsb.withSort(new FieldSortBuilder(sortField).ignoreUnmapped(true).order(order));
		BoolQueryBuilder qb=QueryBuilders. boolQuery();
		for (String key : filedContentMap.keySet()) {//字段查询
			qb.must(QueryBuilders.matchQuery(key,filedContentMap.get(key)));
			
		}
		nsb.withQuery(qb);
		SearchQuery searchQuery = nsb.build();//查询建立
		Page<T> page = null;
		if (heightFields!=null&&heightFields.size() > 0) {//如果设置高亮
			page = elasticsearchTemplate.queryForPage(searchQuery,
					getEntityClass(), new SearchResultMapper() {
						@SuppressWarnings("unchecked")
						@Override
						public <T> Page<T> mapResults(SearchResponse response,Class<T> clazz, Pageable pageable) {
							List<T> chunk = new ArrayList<T>();
							for (SearchHit searchHit : response.getHits()) {
								if (response.getHits().getHits().length <= 0) {
									return null;
								}

								Map<String, Object> entityMap = searchHit.getSource();
								for (String highName : heightFields) {
									String highValue = searchHit.getHighlightFields().get(highName).fragments()[0].toString();
									entityMap.put(highName, highValue);
								}
								chunk.add((T) PropertyHelper.getFansheObj(getEntityClass(), entityMap));
							}
							if (chunk.size() > 0) {
								return new PageImpl<T>((List<T>) chunk);
							}
							return null;
						}

					});
		} else//如果不设置高亮
			page = elasticsearchTemplate.queryForPage(searchQuery,getEntityClass());
		
		return page.getContent();
	}
	/**
	 * 本类查询
	 * 
	 * @param id
	 * @return
	 */
	public T queryById(String id) {
		StringQuery stringQuery = new StringQuery("id=" + id);
		T t = elasticsearchTemplate.queryForObject(stringQuery,
				getEntityClass());
		return t;

	}

	
	
	public ElasticsearchTemplate getElasticsearchTemplate() {
		return elasticsearchTemplate;
	}


	public Client getEsClient() {
		return esClient;
	}



	/**
	 * 得到类型
	 * 
	 * @return
	 */
	public abstract Class<T> getEntityClass();
	/**
	 * 添加各自类的影射
	 */
	public abstract void putClassMapping();
	


	

}
