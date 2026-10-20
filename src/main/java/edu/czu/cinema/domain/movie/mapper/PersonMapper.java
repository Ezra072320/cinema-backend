package edu.czu.cinema.domain.movie.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.movie.entity.Person;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PersonMapper extends BaseMapper<Person> {

    default Person findByName(String name) {
        return selectOne(new LambdaQueryWrapper<Person>().eq(Person::getName, name).last("LIMIT 1"));
    }

    default Person findByMaoyanId(Integer maoyanId) {
        return selectOne(new LambdaQueryWrapper<Person>().eq(Person::getMaoyanId, maoyanId).last("LIMIT 1"));
    }
}